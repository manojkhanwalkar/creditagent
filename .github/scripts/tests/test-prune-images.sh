#!/usr/bin/env bash
# Tests for .github/scripts/prune-images.sh against stubbed `gh` and `curl` (no network).
# Run from anywhere: .github/scripts/tests/test-prune-images.sh
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
script="$here/../prune-images.sh"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
pass=0 fail=0
A=$(printf 'a%.0s' {1..40}) B=$(printf 'b%.0s' {1..40}) C=$(printf 'c%.0s' {1..40})
D=$(printf 'd%.0s' {1..40}) E=$(printf 'e%.0s' {1..40})

# --- stubs ----------------------------------------------------------------------------------
mkdir -p "$tmp/bin"
cat >"$tmp/bin/gh" <<'EOF'
#!/usr/bin/env bash
# Fake gh: listing from $SCEN/versions.json, main head from $SCEN/head, DELETE results from
# $SCEN/delete/<id> (contents: an HTTP status; default 204). $SCEN/fail-list-once makes the first
# listing print one version and then fail with HTTP 503.
if [ "$2" = "-X" ]; then
  id=${4##*/}; status=$(cat "$SCEN/delete/$id" 2>/dev/null || echo 204)
  echo "$id" >>"$SCEN/delete-calls"
  [ "$status" = 204 ] && { echo "$id" >>"$SCEN/deleted"; exit 0; }
  echo "gh: HTTP $status" >&2; exit 1
fi
case "$2" in
  repos/*/commits/main) cat "$SCEN/head"; exit 0 ;;
esac
jq_filter=""; for ((i = 1; i <= $#; i++)); do [ "${!i}" = --jq ] && { j=$((i + 1)); jq_filter=${!j}; }; done
if [ -f "$SCEN/fail-list-once" ]; then
  rm "$SCEN/fail-list-once"
  jq -c "first($jq_filter)" "$SCEN/versions.json"; echo "gh: HTTP 503" >&2; exit 1
fi
jq -c "$jq_filter" "$SCEN/versions.json"
EOF
cat >"$tmp/bin/curl" <<'EOF'
#!/usr/bin/env bash
# Fake curl: /token -> a token; /manifests/<ref> -> $SCEN/manifests/<ref>.json, with the digest
# from $SCEN/manifests/<ref>.digest written to the -D headers file.
url=${*: -1}; headers=""
for ((i = 1; i <= $#; i++)); do [ "${!i}" = -D ] && { j=$((i + 1)); headers=${!j}; }; done
case "$url" in
  *ghcr.io/token*) echo '{"token":"t"}' ;;
  */manifests/*)
    ref=${url##*/manifests/}; ref=${ref//:/_}
    [ -f "$SCEN/manifests/$ref.json" ] || { echo "curl: (22) The requested URL returned error: 404" >&2; exit 22; }
    [ -n "$headers" ] && printf 'Docker-Content-Digest: %s\r\n' "$(cat "$SCEN/manifests/$ref.digest")" >"$headers"
    cat "$SCEN/manifests/$ref.json" ;;
  *) echo "unexpected url $url" >&2; exit 1 ;;
esac
EOF
chmod +x "$tmp/bin/gh" "$tmp/bin/curl"

# --- helpers --------------------------------------------------------------------------------
v() { # id digest created tags-json
  printf '{"id":%s,"name":"sha256:%s","created_at":"%s","metadata":{"container":{"tags":%s}}}' "$1" "$2" "$3" "$4"
}
index() { # scenario ref digest children...
  local scen=$1 ref=$2 digest=$3; shift 3
  mkdir -p "$scen/manifests"
  echo "sha256:$digest" >"$scen/manifests/${ref//:/_}.digest"
  printf '{"manifests":[%s]}' "$(printf '{"digest":"sha256:%s"},' "$@" | sed 's/,$//')" >"$scen/manifests/${ref//:/_}.json"
}
scenario() { local s="$tmp/$1"; mkdir -p "$s/delete"; echo head >"$s/head"; echo "$s"; }
run() { # scenario [env...] -> stdout to $scen/out, rc to $scen/rc
  local s=$1; shift
  set +e
  env SCEN="$s" PATH="$tmp/bin:$PATH" OWNER=o PACKAGE=p GH_TOKEN='t"k\n' REPO=o/r HEAD_SHA=head \
    RETRY_BASE_SECONDS=0 "$@" bash "$script" >"$s/out" 2>"$s/err"
  echo $? >"$s/rc"
  set -e
}
check() { # name actual expected
  if [ "$2" = "$3" ]; then pass=$((pass + 1)); else fail=$((fail + 1)); echo "FAIL $1: got [$2] expected [$3]"; fi
}
deleted() { sort -n "$1/deleted" 2>/dev/null | tr '\n' ' ' | sed 's/ $//'; }

# --- A: the policy on a mixed package --------------------------------------------------------
s=$(scenario A)
cat >"$s/versions.json" <<EOF
[$(v 1 L 2026-11-14T12:00:00Z "[\"sha-$A\",\"latest\"]"),
 $(v 2 L1 2026-11-14T11:59:58Z '[]'), $(v 3 L2 2026-11-14T11:59:59Z '[]'),
 $(v 4 R 2026-11-01T10:00:00Z '["1.2.0","1.2"]'), $(v 5 R1 2026-11-01T09:59:58Z '[]'),
 $(v 6 SHARED 2026-11-01T09:59:59Z '[]'),
 $(v 7 O 2026-11-05T10:00:00Z "[\"sha-$B\"]"), $(v 8 O1 2026-11-05T09:59:58Z '[]'),
 $(v 9 G 2026-11-14T11:55:00Z "[\"sha-$C\"]"),
 $(v 10 M 2026-11-03T10:00:00Z "[\"sha-$D\",\"1.1.0\"]"), $(v 11 M1 2026-11-03T09:59:59Z '[]'),
 $(v 12 DBG 2026-11-02T10:00:00Z '["sha-debug"]'),
 $(v 13 Z 2026-11-02T10:00:00Z null),
 $(v 14 N 2026-11-04T10:00:00Z "[\"sha-$E\"]"), $(v 15 N1 2026-11-04T09:59:58Z '[]')]
EOF
index "$s" latest L L1 L2
index "$s" sha256:R R R1 SHARED
index "$s" sha256:M M M1
index "$s" sha256:G G O1
index "$s" sha256:DBG DBG
# O shares SHARED with release R; in-grace G shares O1 with old build O.
index "$s" sha256:O O O1 SHARED
run "$s"
check "A rc" "$(cat "$s/rc")" 0
# Deleted: old CI builds O and N (+ N1), the untagged orphan Z. Kept: latest+children, release R
# and its children (incl. SHARED), M (sha + 1.1.0) and M1, in-grace G and its child O1, sha-debug.
check "A deleted" "$(deleted "$s")" "7 13 14 15"

# --- B: latest moved while pruning ------------------------------------------------------------
s=$(scenario B)
echo "[$(v 1 L 2026-11-14T12:00:00Z '["latest"]'), $(v 2 O 2026-11-01T10:00:00Z "[\"sha-$B\"]")]" >"$s/versions.json"
index "$s" latest OTHER
run "$s"
check "B rc" "$(cat "$s/rc")" 0
check "B deleted" "$(deleted "$s")" ""
check "B message" "$(grep -c 'latest moved' "$s/out")" 1

# --- C: this run is not the head of main -------------------------------------------------------
s=$(scenario C)
cp "$tmp/B/versions.json" "$s/"; index "$s" latest L; echo other >"$s/head"
run "$s"
check "C deleted" "$(deleted "$s")" ""
check "C message" "$(grep -c 'not the head of main' "$s/out")" 1

# --- D: no latest -------------------------------------------------------------------------------
s=$(scenario D)
echo "[$(v 2 O 2026-11-01T10:00:00Z "[\"sha-$B\"]")]" >"$s/versions.json"
run "$s"
check "D deleted" "$(deleted "$s")" ""
check "D message" "$(grep -c 'no version tagged latest' "$s/out")" 1

# --- E: a version already gone is fine; a 403 is not retried and fails the run ------------------
s=$(scenario E)
echo "[$(v 1 L 2026-11-14T12:00:00Z '["latest"]'), $(v 2 O 2026-11-01T10:00:00Z "[\"sha-$B\"]"), $(v 3 P 2026-11-01T10:00:00Z "[\"sha-$C\"]")]" >"$s/versions.json"
index "$s" latest L
echo 404 >"$s/delete/2"; echo 403 >"$s/delete/3"
run "$s"
check "E rc" "$(cat "$s/rc")" 1
check "E already gone" "$(grep -c 'already gone' "$s/out")" 1
check "E warning" "$(grep -c '::warning::p: could not delete version 3' "$s/out")" 1
check "E 403 tried once" "$(grep -c '^3$' "$s/delete-calls")" 1

# --- F: a transient failure mid-listing is retried without duplicating versions -----------------
s=$(scenario F)
cp "$tmp/E/versions.json" "$s/"; index "$s" latest L; touch "$s/fail-list-once"
run "$s"
check "F rc" "$(cat "$s/rc")" 0
check "F deleted" "$(deleted "$s")" "2 3"
check "F count" "$(grep -c 'deleting 2 of 3' "$s/out")" 1

# --- G: dry run deletes nothing -----------------------------------------------------------------
s=$(scenario G)
cp "$tmp/E/versions.json" "$s/"; index "$s" latest L
run "$s" DRY_RUN=1
check "G deleted" "$(deleted "$s")" ""
check "G would delete" "$(grep -c 'would delete' "$s/out")" 2

echo "prune-images tests: $pass passed, $fail failed"
test "$fail" -eq 0
