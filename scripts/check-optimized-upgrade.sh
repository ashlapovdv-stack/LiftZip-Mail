#!/usr/bin/env bash
set -euo pipefail

package=ru.liftzip.mail
activity="$package/.MainActivity"
baseline=upgrade-baseline-54/app-debug.apk
optimized=app/build/outputs/apk/release/app-release.apk
report=optimized-upgrade-check.txt
trap 'adb logcat -d -s AndroidRuntime:E ActivityManager:W' ERR

adb install -r "$baseline"
adb shell am start -W -n "$activity"
adb shell run-as "$package" mkdir -p files
adb shell run-as "$package" touch files/upgrade-probe
user_dir=$(adb shell run-as "$package" pwd | tr -d '\r')
adb shell am force-stop "$package"
adb logcat -c

adb install -r "$optimized"
adb shell dumpsys package "$package" > optimized-package.txt
version=$(python3 -c 'import json; print(json.load(open("app/build/outputs/apk/release/output-metadata.json"))["elements"][0]["versionName"])')
sed -n 's/^[[:space:]]*versionName=//p' optimized-package.txt | grep -Fx "$version" > "$report"
if grep -Eq 'flags=\[[^]]*DEBUGGABLE' optimized-package.txt; then
    echo "The optimized app must not be debuggable." >&2
    exit 1
fi
adb root
adb wait-for-device
adb shell test -f "$user_dir/files/upgrade-probe"
adb shell am start -W -n "$activity" | tee -a "$report"
grep -Fx "Status: ok" "$report"
pid=$(adb shell pidof "$package" | tr -d '\r')
test -n "$pid"
adb shell uiautomator dump /sdcard/mail-optimized-window.xml
adb shell cat /sdcard/mail-optimized-window.xml | grep -F "$package" > /dev/null
test "$(adb shell pidof "$package" | tr -d '\r')" = "$pid"
adb logcat --pid="$pid" -d -s AndroidRuntime:E > optimized-runtime.txt
if grep -q 'FATAL EXCEPTION' optimized-runtime.txt; then
    cat optimized-runtime.txt >&2
    exit 1
fi
echo "Build 54 app data and launch verified after upgrade." >> "$report"
