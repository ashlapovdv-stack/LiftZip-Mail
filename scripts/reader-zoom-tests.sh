#!/usr/bin/env bash
set -euo pipefail

# The emulator action executes each YAML script line in a separate shell.
# Keep the cleanup trap in the same process as the instrumentation invocation.
collect_reset_screenshots() {
    mkdir -p ui-checks
    for image in reader-shrink-reset-0 reader-shrink-reset-1 reader-shrink-reset-2; do
        if adb shell test -f "/data/local/tmp/liftzip-ui-checks/$image.png"; then
            adb exec-out cat "/data/local/tmp/liftzip-ui-checks/$image.png" > "ui-checks/$image.png"
        fi
    done
}
trap collect_reset_screenshots EXIT
bash ./gradlew --no-daemon :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ru.liftzip.mail.ReaderZoomDeviceTest -Pandroid.testInstrumentationRunnerArguments.timeout_msec=120000
