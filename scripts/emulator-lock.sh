#!/bin/zsh
# Shared script: sync-common keeps every repo's copy identical to the original in the tooling checkout; edit the original only.

# Runs a command holding the machine-wide emulator lock, so every repo's device work takes turns on the one emulator.
# Boots this repo's emulator first and stops it after, since a quickboot costs seconds and an idle emulator costs RAM and battery.
# The command inherits the lock, so killing this script can't free it while the command still runs, and the OS drops it once both are gone.
# Usage: scripts/emulator-lock.sh <command> [args...]
#   e.g. scripts/emulator-lock.sh ./gradlew connectedDebugAndroidTest
#   KEEP_EMULATOR=1 leaves the emulator running for device work that follows right away; scripts/emulator.sh stop stops it later
#   LOCK_WAIT_MINUTES (default 20) is how long to wait for another session's lock before failing; EMULATOR_LOCK overrides the lock path
#   IMAGE_TAG and WINDOW pass through to scripts/emulator.sh

set -e

zmodload zsh/system

if (( $# == 0 )); then
  echo "Usage: $0 <command> [args...]"
  exit 2
fi

SCRIPT_DIR=${0:A:h}

LOCK=${EMULATOR_LOCK:-/tmp/android-emulator.flock}
# Separate from the lock file: closing any descriptor on that file, even one opened only to write it, drops the lock
HOLDER=$LOCK.holder
WAIT_MINUTES=${LOCK_WAIT_MINUTES:-20}

touch "$LOCK"

# -e lets the command inherit the lock
if ! zsystem flock -e -f LOCK_FD -t 0 "$LOCK" 2>/dev/null; then
  echo "Waiting up to $WAIT_MINUTES min for the emulator lock, held by $(cat "$HOLDER" 2>/dev/null)"
  if ! zsystem flock -e -f LOCK_FD -t $(( WAIT_MINUTES * 60 )) "$LOCK"; then
    echo "Gave up after $WAIT_MINUTES min waiting for the emulator lock $LOCK, held by $(cat "$HOLDER" 2>/dev/null)"
    exit 1
  fi
fi

# Names the checkout so waiters in other repos see whose work holds the emulator
print -r -- "pid $$ since $(date '+%H:%M:%S') in ${(D)${SCRIPT_DIR:h}}: $*" > "$HOLDER"
export EMULATOR_LOCK_HELD=1

# The emulator outlives this script, so it must not inherit the lock
"$SCRIPT_DIR/emulator.sh" {LOCK_FD}>&-

STATUS=0
"$@" || STATUS=$?

if [[ -z $KEEP_EMULATOR ]]; then
  "$SCRIPT_DIR/emulator.sh" stop {LOCK_FD}>&-
fi

exit $STATUS
