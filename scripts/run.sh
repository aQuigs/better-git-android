#!/bin/zsh

# Installs the debug build (VARIANT=Release for the minified one) and opens it.

set -e

cd "$(dirname "$0")/.."

./gradlew "install${VARIANT:-Debug}" -q
adb shell am start -W -n com.sqftware.safegit/.MainActivity > /dev/null
