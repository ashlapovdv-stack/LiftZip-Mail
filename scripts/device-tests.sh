#!/usr/bin/env bash
# Keep native UI evidence even when an assertion fails.
set -uo pipefail
device_test_status=0
timeout --kill-after=15s 10m bash ./gradlew --no-daemon :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.timeout_msec=120000 || device_test_status=$?
if [ "$device_test_status" -ne 0 ]; then
    # Preserve the first failure's real diagnostics, not just the Gradle summary.
    # Renderer-crash tests can affect subsequent instrumentation cases; keep the
    # Android process and WebView crash evidence to distinguish cascade from defect.
    adb logcat -d -v threadtime -s AndroidRuntime:E chromium:E cr_ChildProcessConn:E ActivityManager:W TestRunner:I MailReaderReady:I '*:S' > device-timeout.txt || true
    echo "Device test failure: Android runtime and renderer diagnostics saved to device-timeout.txt"
    grep -E 'FATAL EXCEPTION|Process .* has died|onRenderProcessGone|chromium|TestRunner' device-timeout.txt | tail -n 80 || true
fi
mkdir -p ui-checks || exit 1
for image in selection-hidden-inbox citation-final-line collapsed-gap-plain collapsed-gap-author-brs collapsed-gap-paragraph-brs collapsed-gap-wrapper-padding style-forest style-frost style-emerald style-meadow business-artwork-ratios quote-spacing-plain quote-spacing-html quote-spacing-padded selection-standard-before selection-standard-active selection-cozy-before selection-cozy-active selection-business-before selection-business-active plain-order plain-order-comment rich-reply-expanded rich-reply-original compose-rich-paste html-paste-folded html-paste-responsive compose-new compose-copies compose-reply inbox drawer queue partial-panels hidden-panels mail-partial single-unread html-newsletter html-remote html-inline html-long html-fallback html-return style-cozy style-business appearance html-fit edge-standard-buttons edge-cozy-buttons edge-business-buttons edge-standard-gestures edge-cozy-gestures edge-business-gestures edge-hidden-standard-buttons edge-hidden-cozy-buttons edge-hidden-business-buttons edge-hidden-standard-gestures edge-hidden-cozy-gestures edge-hidden-business-gestures row-boundary hide-row-boundary spam-actions compact-attachments expanded-history image-thumbnails html-warm-theme reader-warm-open history-unified-control linked-mime-history; do
    remote_image="/data/local/tmp/liftzip-ui-checks/$image.png"
    if adb shell test -f "$remote_image"; then
        adb exec-out cat "$remote_image" > "ui-checks/$image.png" || device_test_status=1
    elif [ "$device_test_status" -eq 0 ]; then
        echo "Missing required UI image: $image" >&2
        device_test_status=1
    fi
done
exit "$device_test_status"
