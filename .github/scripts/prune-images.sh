#!/usr/bin/env bash
# Prune a GHCR container package down to the build tagged `latest`: its image index plus the
# manifests the index references (the amd64 image and its attestation). Every other version is
# deleted. Run by CI after `latest` was pushed; safe to run by hand with DRY_RUN=1.
#
#   OWNER=<user> PACKAGE=<name> GH_TOKEN=<token> [DRY_RUN=1] .github/scripts/prune-images.sh
#
# Safety:
# - children come from the registry's index for `latest`, never from timestamps;
# - nothing created after the `latest` index is touched (another CI run may be mid-push);
# - if `latest` is missing, or moves while we look, nothing is deleted;
# - a version that's already gone (404) is fine.
set -euo pipefail

: "${OWNER:?}" "${PACKAGE:?}" "${GH_TOKEN:?}"
DRY_RUN="${DRY_RUN:-0}"
api="users/$OWNER/packages/container/$PACKAGE/versions"
accept="application/vnd.oci.image.index.v1+json,application/vnd.docker.distribution.manifest.list.v2+json,application/vnd.oci.image.manifest.v1+json,application/vnd.docker.distribution.manifest.v2+json"

versions=$(gh api "$api?per_page=100" --paginate --jq '.[] | {id, name, created_at, tags: .metadata.container.tags}' | jq -s .)
latest=$(jq -c '[.[] | select(.tags | index("latest"))] | first // empty' <<<"$versions")
if [ -z "$latest" ]; then
  echo "$PACKAGE: no version tagged latest; nothing deleted"
  exit 0
fi
latest_digest=$(jq -r .name <<<"$latest")
latest_created=$(jq -r .created_at <<<"$latest")

registry_token=$(curl -fsS --max-time 30 -u "token:$GH_TOKEN" \
  "https://ghcr.io/token?service=ghcr.io&scope=repository:$OWNER/$PACKAGE:pull" | jq -r .token)
manifest=$(curl -fsS --max-time 30 -D headers.txt -H "Authorization: Bearer $registry_token" \
  -H "Accept: $accept" "https://ghcr.io/v2/$OWNER/$PACKAGE/manifests/latest")
served_digest=$(tr -d '\r' <headers.txt | awk -F': ' 'tolower($1)=="docker-content-digest"{print $2}')
rm -f headers.txt
if [ "$served_digest" != "$latest_digest" ]; then
  echo "$PACKAGE: latest moved while pruning ($latest_digest -> $served_digest); nothing deleted"
  exit 0
fi
children=$(jq -c '[.manifests[]?.digest]' <<<"$manifest")

# Delete: not latest, not one of its children, and not newer than latest (could be in flight).
targets=$(jq -c --arg keep "$latest_digest" --argjson children "$children" --arg before "$latest_created" \
  '[.[] | select(.name != $keep and (.name as $n | $children | index($n) | not) and .created_at <= $before)]' \
  <<<"$versions")
echo "$PACKAGE: keeping $latest_digest (latest) and $(jq length <<<"$children") child manifest(s);" \
  "deleting $(jq length <<<"$targets") of $(jq length <<<"$versions") version(s)"

failed=0
while read -r version; do
  id=$(jq -r .id <<<"$version")
  label="$(jq -r '"\(.name[7:19]) \((.tags // []) | join(","))"' <<<"$version")"
  if [ "$DRY_RUN" = 1 ]; then
    echo "  would delete $label"
  elif out=$(gh api -X DELETE "$api/$id" 2>&1); then
    echo "  deleted $label"
  elif grep -q "HTTP 404" <<<"$out"; then
    echo "  already gone: $label"
  else
    echo "::warning::$PACKAGE: could not delete version $id: $out"
    failed=$((failed + 1))
  fi
done < <(jq -c '.[]' <<<"$targets")
test "$failed" -eq 0
