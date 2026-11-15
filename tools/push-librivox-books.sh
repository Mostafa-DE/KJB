#!/usr/bin/env bash
# Puts real audiobooks on a connected phone for audiobook mode's device tests (BookChaptersDeviceTest,
# LibriVoxBooksDeviceTest): public-domain LibriVox recordings from archive.org, plus two of the generated fixtures.
# Run it by hand from anywhere, with the phone connected over adb:
#
#   tools/push-librivox-books.sh [--with-big-m4b] [CACHE_DIR]
#   tools/push-librivox-books.sh --remove
#
# 1. Download into CACHE_DIR (default "$HOME/Downloads/kjb-librivox"), one folder per book, skipping files already
#    there. curl -fL --retry 3 writes to a .part file first and renames it when complete, so a broken download is
#    fetched again on the next run instead of being skipped.
#      Snark: an .m4b with QuickTime chapters (exactly one chapter) and an .mp3 with no chapters.
#      Aesop 3: the 25 per-chapter MP3s (a folder book). With --with-big-m4b also the book as one .m4b (93 MB,
#        285 QuickTime chapters), the big stress test: opt-in because of its size.
#      Velveteen: an .m4b.
# 2. Push with adb ($ADB, default "adb"; set ANDROID_SERIAL if more than one device is connected). First
#    "$ADB get-state" must say "device", else the script stops. Each book goes to its own folder under
#    "/sdcard/Audiobooks/KJB test/" (Book A to Book D, see the push step). "/sdcard/Music/KJB test/Books outside/"
#    gets files with no book sign from their folder: one more copy of the Snark .m4b (still a book by its extension),
#    the Snark .mp3 (half an hour: music until marked), and with --with-big-m4b the big .m4b again as "Long
#    Fables.m4a" (six hours: music that the song menu suggests marking). The fixtures lantern-chap.mp3 and
#    lantern-chpl-only.m4b (made by tools/make_book_fixtures.py) go to "/sdcard/Audiobooks/KJB test/Fixtures/": no
#    real LibriVox file has CHAP frames or chpl-only chapters. Each folder is made with "mkdir -p" first and each file
#    is pushed to its full path, so the spaces in these paths need no escaping on the adb push side (adb push takes
#    the path as one argument); "adb shell" joins its arguments into one shell line, so paths there are single-quoted.
# 3. Ask MediaStore to scan the shared storage. Files pushed through adb on Android 11+ are usually scanned on their
#    own; this makes sure. A failed scan call only prints a warning.
# 4. Print, and never run, the commands that install the debug build and its test APK and run the device tests
#    (tools/run-book-device-tests.sh). Never use connectedDebugAndroidTest for these: it uninstalls KJB dev afterwards
#    and wipes its data.
#
# --remove deletes "/sdcard/Audiobooks/KJB test" and "/sdcard/Music/KJB test/Books outside" from the phone (the
# rest of "Music/KJB test" stays) and rescans. It downloads nothing.
set -euo pipefail

ADB="${ADB:-adb}"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FIXTURES="$REPO/app/src/androidTest/assets/books"
ARCHIVE="https://archive.org/download"
PHONE_BOOKS="/sdcard/Audiobooks/KJB test"
PHONE_OUTSIDE="/sdcard/Music/KJB test/Books outside"

SNARK_ID="hunting_of_the_snark_librivox"
SNARK_M4B="hunting_of_the_snark_librivox.m4b"
SNARK_MP3="the_hunting_of_the_snark_64kb.mp3"
AESOP_ID="aesop_fables_volume_three_librivox"
AESOP_M4B="AesopsFables64kbps_librivox.m4b"
AESOP_MP3S=()
for n in $(seq -w 1 25); do
  AESOP_MP3S+=("fables_03_${n}_aesop_64kb.mp3")
done
VELVETEEN_ID="velveteen_rabbit_librivox"
VELVETEEN_M4B="velveteen_rabbit_librivox.m4b"

usage() {
  cat <<EOF
Usage: $(basename "$0") [--with-big-m4b] [CACHE_DIR]
       $(basename "$0") --remove

Downloads a few public-domain LibriVox books (into CACHE_DIR, default \$HOME/Downloads/kjb-librivox),
pushes them and two generated fixtures to "$PHONE_BOOKS/" on the connected phone,
rescans, and prints the device-test commands.

  --with-big-m4b  also download and push the 93 MB .m4b with 285 chapters (the stress test)
  --remove        delete "$PHONE_BOOKS" and "$PHONE_OUTSIDE"
                  from the phone, then rescan
  -h, --help      show this

Env: ADB (default "adb"), ANDROID_SERIAL (which phone, when more than one is connected).
EOF
}

# fetch <archive.org identifier> <file> <local folder>
fetch() {
  local out="$3/$2"
  if [ -s "$out" ]; then
    echo "have $out"
    return
  fi
  mkdir -p "$3"
  echo "downloading $2"
  curl -fL --retry 3 -o "$out.part" "$ARCHIVE/$1/$2"
  mv "$out.part" "$out"
}

need_device() {
  local state
  state="$("$ADB" get-state 2>/dev/null)" || state=""
  if [ "$state" != "device" ]; then
    echo "No phone ready: \"$ADB get-state\" says \"${state:-nothing}\". Connect the phone, allow USB debugging" >&2
    echo "on it, check \"$ADB devices\" (set ANDROID_SERIAL if more than one is listed), then run this again." >&2
    exit 1
  fi
}

# push <phone folder> <local file>...
push() {
  local dir="$1" file
  shift
  "$ADB" shell "mkdir -p '$dir'"
  for file in "$@"; do
    "$ADB" push "$file" "$dir/$(basename "$file")"
  done
}

scan() {
  "$ADB" shell content call --uri content://media --method scan_volume --arg external_primary >/dev/null \
    || echo "warning: the MediaStore scan call failed; adb pushes are usually scanned anyway" >&2
}

with_big=0
remove=0
cache="$HOME/Downloads/kjb-librivox"
while [ $# -gt 0 ]; do
  case "$1" in
    -h | --help) usage; exit 0 ;;
    --with-big-m4b) with_big=1 ;;
    --remove) remove=1 ;;
    -*) echo "unknown option: $1" >&2; usage >&2; exit 2 ;;
    *) cache="$1" ;;
  esac
  shift
done

if [ "$remove" = 1 ]; then
  need_device
  "$ADB" shell "rm -rf '$PHONE_BOOKS' '$PHONE_OUTSIDE'"
  scan
  echo "removed \"$PHONE_BOOKS\" and \"$PHONE_OUTSIDE\""
  exit 0
fi

for fixture in lantern-chap.mp3 lantern-chpl-only.m4b; do
  if [ ! -s "$FIXTURES/$fixture" ]; then
    echo "missing $FIXTURES/$fixture: run FFMPEG=/path/to/ffmpeg python3 tools/make_book_fixtures.py first" >&2
    exit 1
  fi
done

# 1. Download.
fetch "$SNARK_ID" "$SNARK_M4B" "$cache/Snark"
fetch "$SNARK_ID" "$SNARK_MP3" "$cache/Snark"
for mp3 in "${AESOP_MP3S[@]}"; do
  fetch "$AESOP_ID" "$mp3" "$cache/Aesop 3"
done
if [ "$with_big" = 1 ]; then
  fetch "$AESOP_ID" "$AESOP_M4B" "$cache/Aesop 3"
fi
fetch "$VELVETEEN_ID" "$VELVETEEN_M4B" "$cache/Velveteen"

# 2. Push.
need_device
# On the phone the folders have neutral names, the ones LibriVoxBooksDeviceTest looks for: Book A (Snark), Book B
# (Aesop 3, its 25 MP3s), Book C (Velveteen), Book D (the big .m4b, alone so it stays a book of its own).
push "$PHONE_BOOKS/Book A" "$cache/Snark/$SNARK_M4B" "$cache/Snark/$SNARK_MP3"
aesop=()
for mp3 in "${AESOP_MP3S[@]}"; do
  aesop+=("$cache/Aesop 3/$mp3")
done
push "$PHONE_BOOKS/Book B" "${aesop[@]}"
if [ "$with_big" = 1 ]; then
  push "$PHONE_BOOKS/Book D" "$cache/Aesop 3/$AESOP_M4B"
fi
push "$PHONE_BOOKS/Book C" "$cache/Velveteen/$VELVETEEN_M4B"
push "$PHONE_OUTSIDE" "$cache/Snark/$SNARK_M4B" "$cache/Snark/$SNARK_MP3"
if [ "$with_big" = 1 ]; then
  "$ADB" push "$cache/Aesop 3/$AESOP_M4B" "$PHONE_OUTSIDE/Long Fables.m4a"
fi
push "$PHONE_BOOKS/Fixtures" "$FIXTURES/lantern-chap.mp3" "$FIXTURES/lantern-chpl-only.m4b"

# 3. Scan.
scan

# 4. The device-test commands, printed only.
cat <<EOF

Pushed. To run the device tests, from $REPO, on the installed debug build and its test APK
(never connectedDebugAndroidTest: it uninstalls KJB dev and wipes its data):

  adb install -r app/build/outputs/apk/debug/app-debug.apk
  adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

One time only, All files access for KJB dev (already granted on your phone; install -r keeps it):

  adb shell appops set --uid app.lyrify.debug MANAGE_EXTERNAL_STORAGE allow

Then (tools/run-book-device-tests.sh runs these and counts passes, skips and failures):

  adb shell am instrument -w -e class app.lyrify.books.BookChaptersDeviceTest app.lyrify.debug.test/androidx.test.runner.AndroidJUnitRunner
  adb shell am instrument -w -e class app.lyrify.books.LibriVoxBooksDeviceTest app.lyrify.debug.test/androidx.test.runner.AndroidJUnitRunner

To take the books off the phone again: $(basename "$0") --remove
EOF
