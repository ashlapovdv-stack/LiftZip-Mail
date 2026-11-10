#!/usr/bin/env bash
# Keep native UI evidence even when an assertion fails.
set -uo pipefail
device_test_status=0
timeout --kill-after=15s 10m bash ./gradlew --no-daemon :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.timeout_msec=120000 || device_test_status=$?
if [ "$device_test_status" -eq 124 ] || [ "$device_test_status" -eq 137 ]; then
    adb logcat -d -s TestRunner:V AndroidRuntime:V MailReaderReady:I '*:S' > device-timeout.txt || true
    tail -n 150 device-timeout.txt
fi
mkdir -p ui-checks || exit 1
for image in inbox drawer queue partial-panels hidden-panels mail-partial single-unread html-newsletter html-remote html-inline html-long html-fallback html-return style-cozy style-business appearance html-fit edge-standard-buttons edge-cozy-buttons edge-business-buttons edge-standard-gestures edge-cozy-gestures edge-business-gestures edge-hidden-standard-buttons edge-hidden-cozy-buttons edge-hidden-business-buttons edge-hidden-standard-gestures edge-hidden-cozy-gestures edge-hidden-business-gestures row-boundary hide-row-boundary spam-actions compact-attachments expanded-history image-thumbnails html-warm-theme; do
    remote_image="/data/local/tmp/liftzip-ui-checks/$image.png"
    if adb shell test -f "$remote_image"; then
        adb exec-out cat "$remote_image" > "ui-checks/$image.png" || device_test_status=1
    elif [ "$device_test_status" -eq 0 ]; then
        echo "Missing required UI image: $image" >&2
        device_test_status=1
    fi
done
exit "$device_test_status"
