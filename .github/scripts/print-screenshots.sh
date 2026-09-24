#!/usr/bin/env bash
# Copies the screens ScreenshotTest rendered off the emulator, prints each into the job log
# as base64 (so the design can be reviewed from the log alone), and leaves them in
# ./screenshots for upload as an artifact. Never fails the build: the images are for review.
set -u
pkg=com.sarvam.voiceassistant
mkdir -p screenshots

files=$(adb shell run-as "$pkg" ls cache/screens 2>/dev/null | tr -d '\r')
if [ -z "$files" ]; then
  echo "No screenshots found on the device."
  exit 0
fi

for f in $files; do
  adb exec-out run-as "$pkg" cat "cache/screens/$f" > "screenshots/$f"
  echo "=== SCREENSHOT $f $(wc -c < "screenshots/$f") bytes"
  base64 -w 150 "screenshots/$f"
  echo "=== END $f"
done
