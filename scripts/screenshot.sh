#!/bin/zsh
# Shared script: sync-common keeps every repo's copy identical to the original in the tooling checkout; edit the original only.

# Captures the connected device's screen to screenshots/<name>.png in this repo and prints the path.
# Usage: scripts/screenshot.sh [name]   (default name: a timestamp; set ANDROID_SERIAL when several devices are connected)

set -e

cd "$(dirname "$0")/.."

NAME=${1:-$(date +%Y%m%d-%H%M%S)}

if [[ $NAME == *[^A-Za-z0-9._-]* ]]; then
  echo "Name '$NAME' may only contain letters, digits, dots, underscores, and dashes"
  exit 1
fi

OUTPUT_FILE="screenshots/$NAME.png"

mkdir -p screenshots
if ! adb exec-out screencap -p > "$OUTPUT_FILE"; then
  rm -f "$OUTPUT_FILE"
  echo "Screenshot failed, is a device connected?"
  exit 1
fi

echo "$OUTPUT_FILE"
