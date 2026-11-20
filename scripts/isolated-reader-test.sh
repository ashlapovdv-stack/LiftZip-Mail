#!/usr/bin/env bash
set -uo pipefail
reader_test="${1:?ReaderDeviceTest method required}"
attempts=1
if [ "$reader_test" = longHtmlCitationSharesScrollingAndRevealsItsFinalLine ]; then attempts=3; fi
status=0
for ((attempt=1; attempt<=attempts; attempt++)); do
    evidence="isolated-evidence/attempt-$attempt"
    mkdir -p "$evidence"
    echo "Reader regression $reader_test, attempt $attempt/$attempts"
    adb logcat -c || true
    bash ./gradlew --no-daemon :app:connectedDebugAndroidTest \
        "-Pandroid.testInstrumentationRunnerArguments.class=ru.liftzip.mail.ReaderDeviceTest#$reader_test" \
        -Pandroid.testInstrumentationRunnerArguments.timeout_msec=120000 || status=$?
    adb logcat -d -v threadtime -s chromium:E cr_A11yState:I AndroidRuntime:E TestRunner:I MailHistoryDiagnosis:E > "$evidence/isolated-log.txt" || true
    adb shell dumpsys window > "$evidence/isolated-window.txt" || true
    adb shell dumpsys activity activities > "$evidence/isolated-activities.txt" || true
    adb shell dumpsys activity processes > "$evidence/isolated-processes.txt" || true
    adb pull /data/local/tmp/liftzip-ui-checks "$evidence/isolated-ui" || true
    cp -r app/build/outputs/androidTest-results "$evidence/" || true
    cp -r app/build/reports/androidTests "$evidence/" || true
done
exit "$status"
