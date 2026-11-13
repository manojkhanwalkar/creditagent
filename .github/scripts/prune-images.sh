#!/usr/bin/env bash
# Prune a GHCR container package of superseded CI builds. A CI build is an image index tagged only
# `sha-<commit>` plus the manifests it references (the amd64 image and its attestation). Every
# version that carries any other tag — `latest`, release tags such as `1.2.0` or `1.2` — is kept
# together with the manifests its index references; superseded CI builds are deleted.
# Run by CI after `latest` was pushed; safe to run by hand with DRY_RUN=1.
#
#   OWNER=<user> PACKAGE=<name> GH_TOKEN=<token> [REPO=<owner/repo> HEAD_SHA=<sha>] \
#     [DRY_RUN=1] .github/scripts/prune-images.sh
#
# Safety:
# - children of kept indexes come from the registry, never from timestamps;
# - nothing created within GRACE_SECONDS (15 min) before the `latest` index, or after it, is
#   touched: a release or manual push may be uploading manifests whose index isn't tagged yet
#   (main CI runs are serialized, so they don't overlap each other);
# - with REPO and HEAD_SHA set, nothing is deleted unless HEAD_SHA is still the head of main, so
#   re-running an old run (which moves `latest` back) can't delete newer builds;
# - if `latest` is missing, or moves while we look, nothing is deleted;
# - remote calls are retried with backoff; a version that's already gone (404) is fine.
set -euo pipefail

: "${OWNER:?}" "${PACKAGE:?}" "${GH_TOKEN:?}"
DRY_RUN="${DRY_RUN:-0}"
GRACE_SECONDS="${GRACE_SECONDS:-900}"
api="users/$OWNER/packages/container/$PACKAGE/versions"
accept="application/vnd.oci.image.index.v1+json,application/vnd.docker.distribution.manifest.list.v2+json,application/vnd.oci.image.manifest.v1+json,application/vnd.docker.distribution.manifest.v2+json"

# retry <command...>: up to 3 attempts, exponential backoff with jitter. Used only for idempotent
# calls (GETs, and DELETEs where "already gone" counts as success).
retry() {
  local attempt
  for attempt in 1 2 3; do
    if "$@"; then return 0; fi
    [ "$attempt" -eq 3 ] && return 1
    sleep $(( (1 << attempt) + RANDOM % 3 ))
  done
}

if [ -n "${REPO:-}" ] && [ -n "${HEAD_SHA:-}" ]; then
  main_head=$(retry gh api "repos/$REPO/commits/main" --jq .sha)
  if [ "$main_head" != "$HEAD_SHA" ]; then
    echo "$PACKAGE: this run ($HEAD_SHA) is not the head of main ($main_head); nothing deleted"
    exit 0
  fi
fi

versions=$(retry gh api "$api?per_page=100" --paginate \
  --jq '.[] | {id, name, created_at, tags: (.metadata.container.tags // [])}' | jq -s .)
latest=$(jq -c '[.[] | select(.tags | index("latest"))] | first // empty' <<<"$versions")
if [ -z "$latest" ]; then
  echo "$PACKAGE: no version tagged latest; nothing deleted"
  exit 0
fi
latest_digest=$(jq -r .name <<<"$latest")
latest_created=$(jq -r .created_at <<<"$latest")

headers=$(mktemp)
curl_auth=$(mktemp)  # mktemp creates it 0600
trap 'rm -f "$headers" "$curl_auth"' EXIT
# Registry token; GH_TOKEN reaches curl through a private config file, never its command line.
printf 'user = "token:%s"\n' "$GH_TOKEN" >"$curl_auth"
registry_token=$(retry curl -fsS --max-time 30 -K "$curl_auth" \
  "https://ghcr.io/token?service=ghcr.io&scope=repository:$OWNER/$PACKAGE:pull" | jq -r .token)

# manifest <reference>: the manifest (or index) JSON; response headers go to $headers.
manifest() {
  retry curl -fsS --max-time 30 -D "$headers" -H "Authorization: Bearer $registry_token" \
    -H "Accept: $accept" "https://ghcr.io/v2/$OWNER/$PACKAGE/manifests/$1"
}

latest_manifest=$(manifest latest)
served_digest=$(tr -d '\r' <"$headers" | awk -F': ' 'tolower($1)=="docker-content-digest"{print $2}')
if [ "$served_digest" != "$latest_digest" ]; then
  echo "$PACKAGE: latest moved while pruning ($latest_digest -> $served_digest); nothing deleted"
  exit 0
fi

# Keep every version with a tag other than sha-* (latest, releases), plus each one's children.
protected=$(jq -c '[.[] | select(any(.tags[]; startswith("sha-") | not)) | .name]' <<<"$versions")
keep=$(jq -c '[.manifests[]?.digest]' <<<"$latest_manifest" | jq -c --argjson p "$protected" '. + $p')
for digest in $(jq -r --arg l "$latest_digest" '.[] | select(. != $l)' <<<"$protected"); do
  keep=$(manifest "$digest" | jq -c --argjson k "$keep" '$k + [.manifests[]?.digest]')
done

# Delete: not kept, tagged only sha-* (or untagged), and old enough that no push can still be
# in progress.
targets=$(jq -c --argjson keep "$keep" --arg latest "$latest_created" --argjson grace "$GRACE_SECONDS" \
  '($latest | fromdateiso8601) - $grace as $cutoff
   | [.[] | select((.name as $n | $keep | index($n) | not)
                   and all(.tags[]; startswith("sha-"))
                   and (.created_at | fromdateiso8601) < $cutoff)]' \
  <<<"$versions")
echo "$PACKAGE: keeping $(jq length <<<"$keep") version(s) (latest, releases and their manifests);" \
  "deleting $(jq length <<<"$targets") of $(jq length <<<"$versions")"

delete_version() {
  local out
  out=$(gh api -X DELETE "$api/$1" 2>&1) && return 0
  grep -q "HTTP 404" <<<"$out" && return 0
  echo "$out" >&2
  return 1
}

failed=0
while read -r version; do
  id=$(jq -r .id <<<"$version")
  label="$(jq -r '"\(.name[7:19]) \(.tags | join(","))"' <<<"$version")"
  if [ "$DRY_RUN" = 1 ]; then
    echo "  would delete $label"
  elif retry delete_version "$id"; then
    echo "  deleted $label"
  else
    echo "::warning::$PACKAGE: could not delete version $id"
    failed=$((failed + 1))
  fi
done < <(jq -c '.[]' <<<"$targets")
test "$failed" -eq 0
