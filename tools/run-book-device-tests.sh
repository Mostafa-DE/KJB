#!/usr/bin/env bash
# Runs audiobook mode's device tests on the connected phone. Only in a phone session the user has said yes to. Put the
# books on the phone first with tools/push-librivox-books.sh; a test whose files are missing is skipped, and counted
# as skipped here.
#
#   tools/run-book-device-tests.sh [--install] [TEST_CLASS...]
#
# --install  "adb install -r" the debug build and its test APK from app/build/outputs first (build them with the lane
#            gate, which runs assembleDebug and assembleDebugAndroidTest). install -r keeps KJB dev's data and grants.
# With no TEST_CLASS it runs BookChaptersDeviceTest, then LibriVoxBooksDeviceTest. A TEST_CLASS can name one method,
# such as app.lyrify.books.LibriVoxBooksDeviceTest#bookBIsOneFolderBookOf25PartsInOrder.
#
# Each class runs on its own with
#   adb shell am instrument -w -r -e class <TEST_CLASS> app.lyrify.debug.test/androidx.test.runner.AndroidJUnitRunner
# never with connectedDebugAndroidTest, which uninstalls KJB dev afterwards and wipes its data. KJB dev needs All files
# access (once: adb shell appops set --uid app.lyrify.debug MANAGE_EXTERNAL_STORAGE allow). The debug build writes only
# in Music/KJB test/ (BookChaptersDeviceTest's folder book, deleted after) and reads Audiobooks/KJB test/; it never
# touches any other file. The raw output of each class is kept in "$OUT" (default build/book-device-tests/).
# The exit status is 0 only when no test failed.
set -euo pipefail

ADB="${ADB:-adb}"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${OUT:-$REPO/build/book-device-tests}"
RUNNER="app.lyrify.debug.test/androidx.test.runner.AndroidJUnitRunner"
DEFAULT_CLASSES=(app.lyrify.books.BookChaptersDeviceTest app.lyrify.books.LibriVoxBooksDeviceTest)

install=0
classes=()
while [ $# -gt 0 ]; do
  case "$1" in
    -h | --help) sed -n '2,20p' "$0"; exit 0 ;;
    --install) install=1 ;;
    -*) echo "unknown option: $1" >&2; exit 2 ;;
    *) classes+=("$1") ;;
  esac
  shift
done
[ ${#classes[@]} -gt 0 ] || classes=("${DEFAULT_CLASSES[@]}")

state="$("$ADB" get-state 2>/dev/null)" || state=""
if [ "$state" != "device" ]; then
  echo "No phone ready: \"$ADB get-state\" says \"${state:-nothing}\". Check \"$ADB devices\" (ANDROID_SERIAL picks one)." >&2
  exit 1
fi

if [ "$install" = 1 ]; then
  "$ADB" install -r "$REPO/app/build/outputs/apk/debug/app-debug.apk"
  "$ADB" install -r "$REPO/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
fi

mkdir -p "$OUT"
failed=0
for class in "${classes[@]}"; do
  log="$OUT/${class##*.}.txt"
  echo "== $class"
  # -r prints a status code per test: 0 passed, -2 failed, -1 error, -3 ignored, -4 skipped (a file not on the phone).
  "$ADB" shell am instrument -w -r -e class "$class" "$RUNNER" | tr -d '\r' >"$log" || true
  passed=$(grep -c '^INSTRUMENTATION_STATUS_CODE: 0$' "$log" || true)
  broke=$(grep -cE '^INSTRUMENTATION_STATUS_CODE: -(1|2)$' "$log" || true)
  skipped=$(grep -cE '^INSTRUMENTATION_STATUS_CODE: -(3|4)$' "$log" || true)
  echo "   passed $passed, failed $broke, skipped $skipped (raw output: $log)"
  if [ "$passed" -eq 0 ]; then
    echo "   nothing passed: are the books on the phone, and the test APK installed?" >&2
    failed=1
  fi
  if [ "$broke" -gt 0 ] || ! grep -q '^INSTRUMENTATION_CODE: -1$' "$log"; then
    failed=1
    grep -A6 '^INSTRUMENTATION_STATUS: stack=' "$log" | head -60 || true
  fi
done

echo "== chapter read times (logcat, tag KJB)"
"$ADB" logcat -d -s KJB:I | grep 'books device test' | tail -20 || true
exit "$failed"
