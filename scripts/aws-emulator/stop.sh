#!/usr/bin/env bash
# Stops the AWS Emulator that start.sh started. Its in-memory resources (tables and their data) are gone.
set -euo pipefail
PORT="${AWS_EMULATOR_PORT:-9297}"
if systemctl --user is-active --quiet "aws-emulator-$PORT" 2>/dev/null; then
  systemctl --user stop "aws-emulator-$PORT"
elif command -v podman >/dev/null && podman container exists "aws-emulator-$PORT"; then
  podman stop -t 10 "aws-emulator-$PORT" >/dev/null && podman rm "aws-emulator-$PORT" >/dev/null
else
  echo "AWS Emulator is not running on port $PORT"; exit 0
fi
echo "AWS Emulator on port $PORT stopped"
