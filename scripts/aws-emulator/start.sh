#!/usr/bin/env bash
# Starts AWS Emulator (https://docs.aws.amazon.com/emulator/) for local development and prepares what
# CreditAgent's JVMs need to use it. Then `source scripts/aws-emulator/env.sh` in each shell that runs a
# service. Stop with scripts/aws-emulator/stop.sh. Everything the emulator holds (tables, reports,
# users) is in memory and is gone when it stops.
#
#   AWS_EMULATOR_PORT       port (default 9297)
#   AWS_EMULATOR_TELEMETRY  on|off (default off: no usage data is sent to AWS)
set -euo pipefail

PORT="${AWS_EMULATOR_PORT:-9297}"
TELEMETRY="${AWS_EMULATOR_TELEMETRY:-off}"
DIR="$HOME/.aws/emulator/$PORT"

IMAGE="localhost/aws-emulator:latest"
CONTAINER="aws-emulator-$PORT"
# AWS's package-signing key (Getting started on Linux: verify the package).
FINGERPRINT="98BCF336A3BE53B734E967C52E5BF49BD940F624"
BASE_URL="https://downloads.aws.com/aws-emulator/packages/aws-emulator/LTS/latest"

build_image() {
  local work; work="$(mktemp -d)"
  trap 'rm -rf "$work"' RETURN
  echo "Building the AWS Emulator image (once)..."
  curl -fsSLo "$work/aws-emulator-x86_64.rpm" "$BASE_URL/aws-emulator-x86_64.rpm"
  curl -fsSLo "$work/aws-emulator-x86_64.rpm.sig" "$BASE_URL/aws-emulator-x86_64.rpm.sig"
  curl -fsSLo "$work/aws-publishing.asc" https://downloads.aws.com/keys/gpg/current.asc
  export GNUPGHOME="$work/gnupg"; mkdir -m 700 "$GNUPGHOME"
  gpg --quiet --import "$work/aws-publishing.asc" 2>/dev/null
  gpg --with-colons --fingerprint ssm-publishing@amazon.com | grep -q "^fpr:::::::::$FINGERPRINT:" \
    || { echo "AWS signing key fingerprint does not match; not installing" >&2; return 1; }
  gpg --status-fd 1 --verify "$work/aws-emulator-x86_64.rpm.sig" "$work/aws-emulator-x86_64.rpm" 2>/dev/null \
    | grep -q "VALIDSIG $FINGERPRINT" || { echo "Bad package signature; not installing" >&2; return 1; }
  cp "$(dirname "$0")/Containerfile" "$work/"
  podman build -q -t "$IMAGE" "$work" >/dev/null
}

if command -v aws-emulator >/dev/null; then
  # Native install (the RPM): a transient user service in the background, capped at 2 GiB and kept
  # out of swap, as AWS recommends; log: journalctl --user -u aws-emulator-$PORT.
  if systemctl --user is-active --quiet "aws-emulator-$PORT"; then
    echo "AWS Emulator is already running on port $PORT"
  else
    systemctl --user reset-failed "aws-emulator-$PORT" 2>/dev/null || true
    systemd-run --user --unit="aws-emulator-$PORT" --quiet -p MemoryMax=2G -p MemorySwapMax=0 \
      aws-emulator -p "$PORT" -t "$TELEMETRY"
  fi
elif command -v podman >/dev/null; then
  # No native install: the same package in a rootless container, published on 127.0.0.1 only.
  podman image exists "$IMAGE" || build_image
  if [[ "$(podman inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null)" == "true" ]]; then
    echo "AWS Emulator is already running on port $PORT"
  else
    mkdir -p "$HOME/.aws/emulator"
    podman rm -f "$CONTAINER" >/dev/null 2>&1 || true
    podman run -d --name "$CONTAINER" --userns=keep-id --memory=2g --memory-swap=2g \
      -p "127.0.0.1:$PORT:$PORT" -e HOME="$HOME" -v "$HOME/.aws/emulator:$HOME/.aws/emulator:Z" \
      "$IMAGE" -p "$PORT" -t "$TELEMETRY" >/dev/null
  fi
else
  echo "Install AWS Emulator (sudo dnf install the RPM) or Podman; see README (Run with AWS Emulator)" >&2
  exit 1
fi

for _ in $(seq 30); do
  if [[ -f "$DIR/ca.pem" ]] && curl -sf --cacert "$DIR/ca.pem" "https://127.0.0.1:$PORT/__admin/health" >/dev/null; then
    break
  fi
  sleep 1
done
curl -sf --cacert "$DIR/ca.pem" "https://127.0.0.1:$PORT/__admin/health" >/dev/null \
  || { echo "AWS Emulator did not become healthy; see journalctl --user -u aws-emulator-$PORT or podman logs aws-emulator-$PORT" >&2; exit 1; }

# Java: the emulator's own truststore.p12 replaces the JVM's trusted certificates, which would break
# Maven and every other HTTPS call. Build one that holds the JDK's certificates plus the emulator's.
# The emulator makes a new certificate on every start, so this is rebuilt every time.
JAVA_BIN="$(readlink -f "$(command -v java)")"
JDK_HOME="${JAVA_HOME:-$(dirname "$(dirname "$JAVA_BIN")")}"
COMBINED="$DIR/creditagent-truststore.p12"
rm -f "$COMBINED"
keytool -importkeystore -noprompt -srckeystore "$JDK_HOME/lib/security/cacerts" -srcstorepass changeit \
  -destkeystore "$COMBINED" -deststoretype PKCS12 -deststorepass changeit >/dev/null 2>&1
keytool -importcert -noprompt -alias aws-emulator-$PORT -file "$DIR/ca.pem" \
  -keystore "$COMBINED" -storetype PKCS12 -storepass changeit >/dev/null
chmod 600 "$COMBINED"

echo "AWS Emulator is ready at https://127.0.0.1:$PORT"
echo "In each shell that runs a CreditAgent service:  source scripts/aws-emulator/env.sh"
