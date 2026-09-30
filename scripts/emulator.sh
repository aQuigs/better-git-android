#!/bin/zsh
# Shared script: sync-common keeps every repo's copy identical to the original in the tooling checkout; edit the original only.

# Boots this repo's AVD as the machine's only emulator, creating the AVD on first use, and waits until Android is ready.
# Holds the machine-wide emulator lock while it works, and stops any other emulator first so plain adb -e stays unambiguous.
# Uses the system image bootstrap.sh installed; this script never installs anything.
# emulator-lock.sh runs it before and after device work; run it directly only to use the emulator yourself.
# Usage: scripts/emulator.sh        (IMAGE_TAG=google_apis for the rootable image, WINDOW=1 to show the emulator window)
#        scripts/emulator.sh stop   stops the running emulator once no session is using it, saving the snapshot that makes the next boot quick
#   LOCK_WAIT_MINUTES (default 20) is how long to wait for the lock; EMULATOR_LOCK overrides the lock path

set -e

zmodload zsh/system

# A missing image must expand to nothing, not to a "no matches" error
setopt null_glob

cd "$(dirname "$0")/.."

IMAGE_TAG=${IMAGE_TAG:-google_apis_playstore}
IMAGE_ROOT=$ANDROID_HOME/system-images

# bootstrap.sh keeps exactly one image per variant, so any other count means it needs re-running
IMAGE_DIRS=("$IMAGE_ROOT"/android-*/"$IMAGE_TAG"/arm64-v8a)
if (( ${#IMAGE_DIRS[@]} != 1 )); then
  echo "Expected one arm64 $IMAGE_TAG system image under $IMAGE_ROOT, found ${#IMAGE_DIRS[@]}"
  echo "Set ANDROID_DEV=1 plus ANDROID_DEV_PLAYSTORE=1 or ANDROID_DEV_ROOT=1 in ~/.zsh_toggles and re-run bootstrap.sh"
  exit 1
fi

RELATIVE_DIR=${IMAGE_DIRS[1]#$IMAGE_ROOT/}
API_DIR=${RELATIVE_DIR%%/*}

# Worktrees share the repo's AVD, so it is named after the main checkout, not the worktree
MAIN_CHECKOUT=$(git rev-parse --path-format=absolute --git-common-dir)
MAIN_CHECKOUT=${MAIN_CHECKOUT:h}

# One AVD per repo, API level, and variant, so a newer image from bootstrap gets a fresh AVD
REPO_NAME=${${MAIN_CHECKOUT:t}//-/_}
AVD_NAME=${REPO_NAME}_${API_DIR#android-}_$IMAGE_TAG
AVD_DIR=$HOME/.android/avd/$AVD_NAME.avd
LOG_FILE=${TMPDIR:-/tmp}/emulator-$AVD_NAME.log

# One lock and one emulator for the whole machine, so every repo's device work takes turns
LOCK=${EMULATOR_LOCK:-/tmp/android-emulator.flock}
HOLDER=$LOCK.holder
WAIT_MINUTES=${LOCK_WAIT_MINUTES:-20}
BOOT_WAIT_MINUTES=5

# Under emulator-lock.sh the lock is already ours, and asking for it again would wait on ourselves
if [[ -z $EMULATOR_LOCK_HELD ]]; then
  touch "$LOCK"
  if ! zsystem flock -t 0 "$LOCK" 2>/dev/null; then
    echo "Waiting up to $WAIT_MINUTES min for the emulator lock, held by $(cat "$HOLDER" 2>/dev/null)"
    if ! zsystem flock -t $(( WAIT_MINUTES * 60 )) "$LOCK"; then
      echo "Gave up after $WAIT_MINUTES min waiting for the emulator lock $LOCK, held by $(cat "$HOLDER" 2>/dev/null)"
      exit 1
    fi
  fi
  print -r -- "pid $$ since $(date '+%H:%M:%S') in ${(D)PWD}: scripts/emulator.sh $*" > "$HOLDER"
fi

emulator_serials() {
  adb devices | awk '/^emulator-/ {print $1}'
}

# Holding the lock, any running emulator is idle: a KEEP_EMULATOR leftover or one started outside the lock
stop_emulators() {
  local serial
  for serial in ${(f)"$(emulator_serials)"}; do
    echo "Stopping $(adb -s "$serial" emu avd name 2>/dev/null | head -1 | tr -d '\r') on $serial"
    adb -s "$serial" emu kill > /dev/null
  done

  # The console closes before the snapshot is saved, so the process exiting is the real signal
  repeat 120 {
    if ! pgrep -f -- '-avd ' > /dev/null; then
      return 0
    fi
    sleep 1
  }
  echo "An emulator is still running 2 min after it was told to stop: $(pgrep -lf -- '-avd ')"
  exit 1
}

if [[ $1 == stop ]]; then
  stop_emulators
  echo "No emulator is running"
  exit 0
fi

# adb -e ignores plugged-in phones, and with a single emulator it cannot pick the wrong one
SERIALS=(${(f)"$(emulator_serials)"})
if (( ${#SERIALS} == 1 )) && [[ $(adb -e emu avd name 2>/dev/null | head -1 | tr -d '\r') == "$AVD_NAME" ]]; then
  echo "$AVD_NAME is already running"
  exit 0
fi

stop_emulators

if [[ ! -d $AVD_DIR ]]; then
  echo "Creating AVD $AVD_NAME"
  # no declines the custom hardware profile prompt; pixel_8 is a device profile that supports Play Store images
  echo no | avdmanager create avd --name "$AVD_NAME" --package "system-images;$API_DIR;$IMAGE_TAG;arm64-v8a" --device pixel_8

  # avdmanager writes hw.keyboard=no, which blocks typing from the host keyboard
  sed -i '' '/^hw.keyboard=/d' "$AVD_DIR/config.ini"
  echo 'hw.keyboard=yes' >> "$AVD_DIR/config.ini"
fi

WINDOW_ARGS=(-no-window)
if [[ -n $WINDOW ]]; then
  WINDOW_ARGS=()
fi

echo "Booting $AVD_NAME, log at $LOG_FILE"
# WebView, and so Google sign-in, aborts on the host GPU translator with an empty GL version; SwiftShader is the only renderer where it survives
emulator -avd "$AVD_NAME" -gpu swiftshader_indirect -no-boot-anim $WINDOW_ARGS > "$LOG_FILE" 2>&1 &
EMULATOR_PID=$!

DEADLINE=$(( SECONDS + BOOT_WAIT_MINUTES * 60 ))
until [[ $(adb -e shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') == 1 ]]; do
  if ! kill -0 "$EMULATOR_PID" 2>/dev/null; then
    echo "Emulator exited before boot completed, see $LOG_FILE"
    exit 1
  fi

  if (( SECONDS > DEADLINE )); then
    echo "$AVD_NAME did not finish booting within $BOOT_WAIT_MINUTES min, stopping it; see $LOG_FILE"
    kill "$EMULATOR_PID"
    exit 1
  fi

  sleep 2
done

# Taps show up in screen recordings; it's a persisted system setting, so re-applying each boot also covers fresh AVDs
adb -e shell settings put system show_touches 1

echo "Emulator $AVD_NAME is ready"
