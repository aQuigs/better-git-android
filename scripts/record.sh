#!/bin/zsh
# Shared script: sync-common keeps every repo's copy identical to the original in the tooling checkout; edit the original only.

# Records the connected device's screen to screenshots/<name>.mp4 in this repo and prints the path.
# Usage: scripts/record.sh [name] [seconds]
#   defaults: a timestamp and 10 seconds; screenrecord allows at most 180; set ANDROID_SERIAL when several devices are connected

set -e

cd "$(dirname "$0")/.."

NAME=${1:-$(date +%Y%m%d-%H%M%S)}
DURATION=${2:-10}

if [[ $NAME == *[^A-Za-z0-9._-]* ]]; then
  echo "Name '$NAME' may only contain letters, digits, dots, underscores, and dashes"
  exit 1
fi

if [[ ! $DURATION =~ ^[0-9]+$ ]] || (( DURATION < 1 || DURATION > 180 )); then
  echo "Seconds must be a whole number from 1 to 180, got '$DURATION'"
  exit 1
fi

OUTPUT_FILE="screenshots/$NAME.mp4"
REMOTE_FILE="/sdcard/$NAME.mp4"

mkdir -p screenshots

echo "Recording $DURATION seconds" >&2
adb shell screenrecord --time-limit "$DURATION" "$REMOTE_FILE"
adb pull -q "$REMOTE_FILE" "$OUTPUT_FILE"
adb shell rm "$REMOTE_FILE"

echo "$OUTPUT_FILE"
