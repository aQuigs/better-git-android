#!/bin/bash
# Drives the debug build on the emulator through a whole sync story against a repo this Mac serves, playing the laptop
# itself: connect an empty folder, sync, conflict on one line, resolve it by editing, then choose a version of a binary.
# Then automatic syncs: one pulls, one waits while a file is being saved, and one leaves a conflict for the user.
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
# Runs the command after the message until it succeeds, for at most WITHIN seconds
within() { local why=$1; shift; local end=$((SECONDS + ${WITHIN:-60})); until "$@"; do [ $SECONDS -lt $end ] || fail "$why"; sleep 1; done; }
shown() { [ -n "$(center "$1")" ]; }
stopped() { adb shell dumpsys activity -p $APP activities | grep -q 'state=STOPPED'; }
await() { WITHIN=${2:-60} within "no '$1' within ${2:-60} s" shown "$1"; }
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

echo "== The card says what the next sync pushes"
await "No changes on this phone to sync" 10
phone_writes daily/2026-10-13.md '- wrote the e2e\n- and ran it\n'
phone_writes ideas.md 'a new idea\n'
# Read as the app comes to the front, not while it shows; Android stops it a second or two after Home
adb shell input keyevent KEYCODE_HOME
WITHIN=10 within "the app never went to the back" stopped
adb shell am start -n $APP/.MainActivity >/dev/null
await "To sync: 1 changed, 1 new ▾" 10
tap "To sync: 1 changed, 1 new ▾"
await "ideas.md" 10
shot pending
tap "Sync"
await "No changes on this phone to sync"
shot pending-none
laptop pull -q

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

# Turning automatic syncs on runs the first right away, which is what starts each run here
auto_on() {
  tap "Auto sync: Off"
  tap "Every 15 minutes"
  await "Auto sync: Every 15 minutes" 10
}
auto_off() {
  tap "Auto sync: Every 15 minutes"
  tap "Off"
  await "Auto sync: Off" 10
}
on_phone() { within "$1 never reached the phone" adb shell "[ -e $FOLDER/$1 ]"; }
pulled() { laptop pull -q 2>/dev/null && [ -e $WORK/laptop/$1 ]; }
pushed() { within "$1 never reached the laptop" pulled "$1"; }
finished() { adb logcat -d | grep -q "Worker result SUCCESS"; }
worker_done() { within "no sync worker finished" finished; adb logcat -c; }
notice() { adb shell dumpsys notification --noredact | grep -q "$1"; }
markers() { adb shell "grep -q '^<<<<<<<' $FOLDER/$1"; }
backdate() { adb shell touch -m -d 2020-01-01T00:00:00 $FOLDER/$1; }
# WorkManager runs a job forced before a periodic run is due only once its time comes, so this starts the one-off ones
WM_JOBS=androidx.work.systemjobscheduler
run_jobs() { for id in $(adb shell dumpsys jobscheduler | grep -oE "^  JOB $WM_JOBS:u[0-9a-z]+/[0-9]+: [0-9a-f]+ .*@$APP/" | sed -E 's|^  JOB [^/]*/([0-9]+):.*|\1|' | sort -u); do adb shell cmd jobscheduler run -f -n $WM_JOBS $APP $id >/dev/null; done; }

echo "== Automatic syncs"
adb shell pm grant $APP android.permission.POST_NOTIFICATIONS
printf 'from the laptop\n' > $WORK/laptop/later.md
laptop add later.md && laptop commit -qm "Later" && laptop push -q
auto_on
on_phone later.md
await "Synced"
shot auto

echo "== An automatic sync waits while a file is being saved"
auto_off
adb logcat -c
phone_writes draft.md 'half written\n'
printf 'more from the laptop\n' > $WORK/laptop/more.md
laptop add more.md && laptop commit -qm "More" && laptop push -q
auto_on
worker_done
adb shell "[ -e $FOLDER/more.md ]" && fail "synced while a file was being saved"
backdate draft.md
run_jobs
on_phone more.md
pushed draft.md

echo "== An automatic sync leaves a conflict for the user"
auto_off
phone_writes welcome.md '# Welcome\n\nThese notes sync with SafeGit.\n\nToday: phone again.\n'
backdate welcome.md
laptop pull -q
printf '# Welcome\n\nThese notes sync with SafeGit.\n\nToday: laptop again.\n' > $WORK/laptop/welcome.md
laptop commit -qam "Laptop again" && laptop push -q
auto_on
within "no notification of the conflict" notice "changed the same lines"
markers welcome.md && fail "an automatic sync wrote markers"
await "welcome.md"
shot conflict-ahead
adb shell cmd statusbar expand-notifications
sleep 2
shot notification
adb shell cmd statusbar collapse
sleep 1
tap "Sync"
within "Sync wrote no markers" markers welcome.md
notice "changed the same lines" && fail "the notification outlived the sync that showed the conflict"

echo "E2E passed; shots in $OUT"
