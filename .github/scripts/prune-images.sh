#!/usr/bin/env bash
# Prune a GHCR container package of superseded CI builds. A CI build is an image index tagged only
# `sha-<40-hex commit>` (docker/metadata-action, format=long) plus the manifests it references
# (the amd64 image and its attestation). Every version with any other tag — `latest`, release tags
# such as `1.2.0` or `1.2`, a hand-pushed `sha-debug` — is kept, and so is every manifest that any
# kept index references. Run by CI after `latest` was pushed; safe to run by hand with DRY_RUN=1.
# Tests: .github/scripts/tests/test-prune-images.sh
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
# - transient failures (HTTP 5xx/429, timeouts, connection errors) are retried with backoff;
#   anything else fails at once; a version that's already gone (404) is fine.
set -euo pipefail

: "${OWNER:?}" "${PACKAGE:?}" "${GH_TOKEN:?}"
DRY_RUN="${DRY_RUN:-0}"
GRACE_SECONDS="${GRACE_SECONDS:-900}"
RETRY_BASE_SECONDS="${RETRY_BASE_SECONDS:-1}"
api="users/$OWNER/packages/container/$PACKAGE/versions"
accept="application/vnd.oci.image.index.v1+json,application/vnd.docker.distribution.manifest.list.v2+json,application/vnd.oci.image.manifest.v1+json,application/vnd.docker.distribution.manifest.v2+json"
ci_tag='^sha-[0-9a-f]{40}$'

work=$(mktemp -d)  # 0700; holds the curl credentials, headers and command output
trap 'rm -rf "$work"' EXIT

# retry <command...>: run it; on a transient failure retry up to 3 attempts in all, with
# exponential backoff and jitter. Stdout goes to stdout only from the successful attempt.
retry() {
  local attempt
  for attempt in 1 2 3; do
    if "$@" >"$work/out" 2>"$work/err"; then
      cat "$work/out"
      return 0
    fi
    if [ "$attempt" -eq 3 ] \
      || ! grep -Eq 'HTTP (5[0-9]{2}|429)|error: (5[0-9]{2}|429)|timed out|curl: \((6|7|28|52|56)\)|connection (reset|refused)' "$work/err"; then
      cat "$work/err" >&2
      return 1
    fi
    sleep $(( RETRY_BASE_SECONDS * (1 << attempt) + RANDOM % 3 ))
  done
}

if [ -n "${REPO:-}" ] && [ -n "${HEAD_SHA:-}" ]; then
  main_head=$(retry gh api "repos/$REPO/commits/main" --jq .sha)
  if [ "$main_head" != "$HEAD_SHA" ]; then
    echo "$PACKAGE: this run ($HEAD_SHA) is not the head of main ($main_head); nothing deleted"
    exit 0
  fi
fi

# The whole paginated listing is one retried unit, so a page that fails mid-way can't duplicate
# the pages before it.
versions=$(retry gh api "$api?per_page=100" --paginate \
  --jq '.[] | {id, name, created_at, tags: (.metadata.container.tags // [])}' \
  | jq -s 'unique_by(.id)')
latest=$(jq -c '[.[] | select(.tags | index("latest"))] | first // empty' <<<"$versions")
if [ -z "$latest" ]; then
  echo "$PACKAGE: no version tagged latest; nothing deleted"
  exit 0
fi
latest_digest=$(jq -r .name <<<"$latest")
latest_created=$(jq -r .created_at <<<"$latest")

# Registry token; GH_TOKEN reaches curl through a private config file, never its command line.
token_escaped=${GH_TOKEN//\\/\\\\}
token_escaped=${token_escaped//\"/\\\"}
printf 'user = "token:%s"\n' "$token_escaped" >"$work/auth"
registry_token=$(retry curl -fsS --max-time 30 -K "$work/auth" \
  "https://ghcr.io/token?service=ghcr.io&scope=repository:$OWNER/$PACKAGE:pull" | jq -r .token)
printf 'header = "Authorization: Bearer %s"\n' "$registry_token" >"$work/bearer"

# manifest <reference>: the manifest (or index) JSON; response headers go to $work/headers.
manifest() {
  retry curl -fsS --max-time 30 -D "$work/headers" -K "$work/bearer" \
    -H "Accept: $accept" "https://ghcr.io/v2/$OWNER/$PACKAGE/manifests/$1"
}

latest_manifest=$(manifest latest)
served_digest=$(tr -d '\r' <"$work/headers" | awk -F': ' 'tolower($1)=="docker-content-digest"{print $2}')
if [ "$served_digest" != "$latest_digest" ]; then
  echo "$PACKAGE: latest moved while pruning ($latest_digest -> $served_digest); nothing deleted"
  exit 0
fi

# Candidates: tagged only with CI tags (or untagged) and older than the grace window.
candidates=$(jq -c --arg ci "$ci_tag" --arg latest "$latest_created" --argjson grace "$GRACE_SECONDS" \
  '($latest | fromdateiso8601) - $grace as $cutoff
   | [.[] | select(all(.tags[]; test($ci)) and (.created_at | fromdateiso8601) < $cutoff)]' \
  <<<"$versions")
# Keep every non-candidate, and every manifest any non-candidate index references (an index
# inside the grace window may share a child with an old build).
keep=$(jq -c --argjson c "$candidates" '[.[] | select(.name as $n | $c | map(.name) | index($n) | not) | .name]' <<<"$versions")
keep=$(jq -c --argjson k "$keep" '$k + [.manifests[]?.digest] | unique' <<<"$latest_manifest")
for digest in $(jq -r --arg l "$latest_digest" --argjson c "$candidates" \
    '.[] | select(.tags != [] and .name != $l) | .name as $n
     | select($c | map(.name) | index($n) | not) | $n' <<<"$versions"); do
  keep=$(manifest "$digest" | jq -c --argjson k "$keep" '$k + [.manifests[]?.digest] | unique')
done
targets=$(jq -c --argjson keep "$keep" '[.[] | select(.name as $n | $keep | index($n) | not)]' <<<"$candidates")
echo "$PACKAGE: keeping $(jq length <<<"$keep") version(s) (latest, releases, recent pushes and their manifests);" \
  "deleting $(jq length <<<"$targets") of $(jq length <<<"$versions")"

# delete_version <id>: 0 deleted, 2 already gone, 1 failed.
delete_version() {
  if retry gh api -X DELETE "$api/$1" >/dev/null 2>"$work/delete-err"; then
    return 0
  fi
  grep -q "HTTP 404" "$work/delete-err" && return 2
  cat "$work/delete-err" >&2
  return 1
}

failed=0
while read -r version; do
  id=$(jq -r .id <<<"$version")
  label="$(jq -r '"\(.name[7:19]) \(.tags | join(","))"' <<<"$version")"
  if [ "$DRY_RUN" = 1 ]; then
    echo "  would delete $label"
    continue
  fi
  status=0
  delete_version "$id" || status=$?
  case $status in
    0) echo "  deleted $label" ;;
    2) echo "  already gone: $label" ;;
    *) echo "::warning::$PACKAGE: could not delete version $id"; failed=$((failed + 1)) ;;
  esac
done < <(jq -c '.[]' <<<"$targets")
test "$failed" -eq 0
