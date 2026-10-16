#!/bin/bash
# Drives the debug build on the emulator through a whole sync story against a repo this Mac serves, playing the laptop
# itself: connect an empty folder, sync, conflict on one line, resolve it by editing, then choose a version of a binary.
# Wipes the app's data and /sdcard/Documents/Notes. Shots land in screenshots/e2e/.
# Usage: ./gradlew assembleDebug && scripts/emulator-lock.sh scripts/e2e.sh   (VARIANT=release after assembleRelease)

set -u
cd "$(dirname "$0")/.."

PORT=8418
VARIANT=${VARIANT:-debug}
APP=com.sqftware.safegit
FOLDER=/sdcard/Documents/Notes
OUT=screenshots/e2e
WORK=$(mktemp -d)
mkdir -p $OUT
rm -f $OUT/*.png

dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml; }
texts() { dump | python3 -c "import sys,re,html; print([html.unescape(t) for t in re.findall(r' text=\"([^\"]+)\"', sys.stdin.read())])"; }
# "x y" of the first node whose text or description is $1, ignoring case
center() {
  dump | python3 -c "
import sys,re,html
want=sys.argv[1].lower()
for n in re.findall(r'<node [^>]*>', sys.stdin.read()):
    label=lambda a: html.unescape((re.search(a+r'=\"([^\"]*)\"',n) or [0,''])[1]).lower()
    if want in (label(' text'), label('content-desc')):
        x1,y1,x2,y2=map(int,re.search(r'bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"',n).groups()); print((x1+x2)//2,(y1+y2)//2); break
" "$1"
}
tap() { local at; at=$(center "$1"); [ -n "$at" ] || fail "no '$1' on screen"; adb shell input tap $at; sleep 1; }
await() { local end=$((SECONDS + ${2:-60})); until [ -n "$(center "$1")" ]; do [ $SECONDS -lt $end ] || fail "no '$1' within ${2:-60} s"; sleep 1; done; }
# Back closes only the keyboard while it is up
shot() { adb shell dumpsys input_method | grep -q 'mInputShown=true' && adb shell input keyevent 4 && sleep 1; adb exec-out screencap -p > $OUT/$1.png; }
fail() { echo "FAILED: $*"; adb exec-out screencap -p > $OUT/failed.png; texts; exit 1; }
laptop() { git -C $WORK/laptop -c user.name=Laptop -c user.email=laptop@example.com "$@"; }
phone_writes() { adb shell "printf '$2' > $FOLDER/$1"; }

echo "== Remote and laptop"
git init -q --bare -b main $WORK/remote.git
git -C $WORK/remote.git config http.receivepack true
git clone -q $WORK/remote.git $WORK/laptop 2>/dev/null
mkdir -p $WORK/laptop/daily
printf '# Welcome\n\nThese notes sync with SafeGit.\n\nToday: nothing yet.\n' > $WORK/laptop/welcome.md
printf -- '- wrote the e2e\n' > $WORK/laptop/daily/2026-10-13.md
printf '\211PNG\000first' > $WORK/laptop/photo.png
laptop add --all && laptop commit -qm "First notes" && laptop push -q origin main
scripts/git-http-server.py $WORK $PORT &
SERVER=$!
trap 'kill $SERVER; rm -rf $WORK' EXIT
# The emulator's apps cannot reach the host at 10.0.2.2, so the phone sees the server on its own loopback
adb reverse tcp:$PORT tcp:$PORT >/dev/null

echo "== Fresh app, empty folder"
adb install -r app/build/outputs/apk/$VARIANT/app-$VARIANT.apk >/dev/null || fail "install"
adb shell appops set --uid $APP MANAGE_EXTERNAL_STORAGE default
adb shell am force-stop $APP
adb shell pm clear $APP >/dev/null
adb shell rm -rf $FOLDER
adb shell mkdir -p $FOLDER
adb shell am start -n $APP/.MainActivity >/dev/null
await "Allow access" 20
shot access
# Granting it in Settings takes taps that differ by Android version
adb shell appops set --uid $APP MANAGE_EXTERNAL_STORAGE allow
sleep 2
adb shell am force-stop $APP
adb shell am start -n $APP/.MainActivity >/dev/null
await "Add a repo" 20
shot empty

echo "== Connect"
tap "Add a repo"
await "Repo URL" 10
tap "Repo URL"
adb shell input text "http://127.0.0.1:$PORT/remote.git"
tap "Choose folder"
await "Documents" 10
tap "Documents"
await "Notes" 10
tap "Notes"
sleep 2
tap "USE THIS FOLDER"
await "ALLOW" 10
tap "ALLOW"
await "Connect" 10
shot add
tap "Connect"
await "Synced"
shot synced
[ "$(adb shell cat $FOLDER/welcome.md)" == "$(cat $WORK/laptop/welcome.md)" ] || fail "welcome.md did not arrive"

echo "== Both change the same line"
phone_writes welcome.md '# Welcome\n\nThese notes sync with SafeGit.\n\nToday: phone wants to read.\n'
printf '# Welcome\n\nThese notes sync with SafeGit.\n\nToday: laptop wants to write.\n' > $WORK/laptop/welcome.md
laptop commit -qam "Laptop's plan" && laptop push -q
tap "Sync"
await "welcome.md"
shot conflict
adb shell "grep -q '^>>>>>>> remote' $FOLDER/welcome.md" || fail "no markers in welcome.md"
[ "$(git -C $WORK/remote.git log -1 --format=%s)" == "Laptop's plan" ] || fail "pushed while markers were left"

echo "== The phone resolves it"
phone_writes welcome.md '# Welcome\n\nThese notes sync with SafeGit.\n\nToday: read, then write.\n'
tap "Sync"
sleep 2
await "Synced"
laptop pull -q
grep -q "read, then write" $WORK/laptop/welcome.md || fail "the resolution did not reach the laptop"

echo "== Both change a binary file"
phone_writes photo.png '\\211PNG\\000phone'
printf '\211PNG\000laptop' > $WORK/laptop/photo.png
laptop commit -qam "Laptop's photo" && laptop push -q
tap "Sync"
await "photo.png"
shot choose
tap "Keep this phone's"
shot chosen
tap "Sync with these choices"
sleep 2
await "Synced"
laptop pull -q
grep -q phone $WORK/laptop/photo.png || fail "the phone's photo did not reach the laptop"

echo "E2E passed; shots in $OUT"
