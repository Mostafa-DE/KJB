#!/usr/bin/env bash
# Puts real audiobooks on a connected phone for audiobook mode's device tests (BookChaptersDeviceTest,
# LibriVoxBooksDeviceTest): public-domain LibriVox recordings from archive.org, plus two of the generated fixtures.
# Run it by hand from anywhere, with the phone connected over adb:
#
#   tools/push-librivox-books.sh [--with-big-m4b] [CACHE_DIR]
#   tools/push-librivox-books.sh --remove
#
# 1. Download into CACHE_DIR (default "$HOME/Downloads/kjb-librivox"), one folder per book, skipping a file already
#    there only when its SHA-256 is the one pinned below. curl (HTTPS only, redirects too) writes to a .part file, and
#    the file is renamed only once its SHA-256 matches, so a broken or changed download is never kept or pushed.
#    The pins were taken on 2026-11-15 from files whose md5, sha1 and size matched archive.org's own lists
#    (https://archive.org/download/<identifier>/<identifier>_files.xml). A file failing its check is fetched again;
#    a download that still fails it stops the script.
#      Snark: an .m4b with QuickTime chapters (exactly one chapter) and an .mp3 with no chapters.
#      Aesop 3: the 25 per-chapter MP3s (a folder book). With --with-big-m4b also the book as one .m4b (93 MB,
#        285 QuickTime chapters), the big stress test: opt-in because of its size.
#      Velveteen: an .m4b.
# 2. Push with adb ($ADB, default "adb"; set ANDROID_SERIAL if more than one device is connected). Every LibriVox
#    file is checked against its pin again right before it is pushed, the cached ones too. First
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

# The SHA-256 of each LibriVox file, by its archive.org name (see step 1 for where they come from).
declare -A SHA256=(
  [hunting_of_the_snark_librivox.m4b]=29f3a9d2771bf757e8e1f7b692b26127a19d9fa8352b86b07257e13ef4923253
  [the_hunting_of_the_snark_64kb.mp3]=1a0a8bd192485f833215569ddaabec36beba75b34b03ac442672827d8544f632
  [AesopsFables64kbps_librivox.m4b]=f23a71511863842bc9a31a788761ca1f949109c759fb159c3bee6a70790b5cc9
  [fables_03_01_aesop_64kb.mp3]=71902150fefc0fb6b1af9907a2bb6689f977530ff7b92c4b0446ace387554cba
  [fables_03_02_aesop_64kb.mp3]=08b6824f76d24ceeb3bfc340c7af90462dc1a6e7682c4e09a1aacfd3286dae9f
  [fables_03_03_aesop_64kb.mp3]=5fbef33ad2523abe9a4cfe2b409a9cb957ecc50a1ac454199966c90fd79661e6
  [fables_03_04_aesop_64kb.mp3]=e764191c38f9acf8634acfa32680034b5b7843d4be5fee9d69804514586a8599
  [fables_03_05_aesop_64kb.mp3]=12bf43a277d1da9314ab460999e9117339018b72d58f710d33eaaf92dc7f567b
  [fables_03_06_aesop_64kb.mp3]=284315f5bb13c75c7c132d4c0c168b8c82a8f684b5868d321b84b3b752a00c0f
  [fables_03_07_aesop_64kb.mp3]=547d28d80ae07fbcb95f7abe5cd3c7d9e3985724a7c61c4992ddd69eb4292f11
  [fables_03_08_aesop_64kb.mp3]=d991f7f0add95e94c52972fa9e603867a86fa84ec4cfbbaddfa57d18a7cd3863
  [fables_03_09_aesop_64kb.mp3]=036c95dd117ce14cec2ba1711cfcae58016ca11cef7310aede29a7688209f094
  [fables_03_10_aesop_64kb.mp3]=cd6bca9f99dd9484f90602471dbbfe054243044dd8787841a491f7a14bc083b9
  [fables_03_11_aesop_64kb.mp3]=dec690475d214535582e8b4f07e2c239b4baa174115d7b07242be0ad54bf3cdb
  [fables_03_12_aesop_64kb.mp3]=ab3f1e8b09d59746ceb3a7b9fa8e4bf3437215bc2cf82d63d25885143c17e980
  [fables_03_13_aesop_64kb.mp3]=123a5d0eb2b2bf88d4bb9940231976b7cd17548369b58ea69d75bd4652aafcca
  [fables_03_14_aesop_64kb.mp3]=171165d4c759e898bbdb893295f6bef018b47749f4801ff3714734414a456179
  [fables_03_15_aesop_64kb.mp3]=10da3ec739441d743e157c84db37b57be54db22f24524695d827c6a189179af4
  [fables_03_16_aesop_64kb.mp3]=c04f010deafee828b0ff419d5fedd33f26068e1f11f3f786020af4bad835a5ef
  [fables_03_17_aesop_64kb.mp3]=0a129567eac3fcec65ca3b0db0b85ad53f028cb871cf46daf49626ea0f80306f
  [fables_03_18_aesop_64kb.mp3]=2c905d57f55905272aea02d028bb4e26a3362b3979b79d309afe23058dfc95ba
  [fables_03_19_aesop_64kb.mp3]=56edb6041f167512dd39d35678c3fbbfdcad61c642648701386b5850c61088f9
  [fables_03_20_aesop_64kb.mp3]=044cdd9c658e66e2882815e15964ca6ba6fab333361c6256f057e76c532a22ce
  [fables_03_21_aesop_64kb.mp3]=2f1c2388e4d050de6dc093c2743f08a8360a39821c6385ee557fbe50b626101b
  [fables_03_22_aesop_64kb.mp3]=bc04584eea9730a41d958ea21b6757c27e58784271ead62beaac7d03d2649970
  [fables_03_23_aesop_64kb.mp3]=a9fa7b93a90486fdfc6ef9668a980cbdff15c6190f4d9023ddb941365ab8b95f
  [fables_03_24_aesop_64kb.mp3]=25f96deaafd18bcf66345fa6b411775482c0bc116867a16cb54041f87a5cb945
  [fables_03_25_aesop_64kb.mp3]=2332fc2bc61656a85e8ef9957db95cdbfcee20288658fca3aee3a597ce13b048
  [velveteen_rabbit_librivox.m4b]=6bcc029eca69081e0c895fa1fbd0d62337ac2c06a28e7a19278d2bdc5cf98b7a
)

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

# pinned <local file> <archive.org name>: true when the file is there and its SHA-256 is that name's pin.
pinned() {
  local want="${SHA256[$2]:-}"
  [ -n "$want" ] && [ -s "$1" ] && [ "$(sha256sum "$1" | cut -d ' ' -f 1)" = "$want" ]
}

# fetch <archive.org identifier> <file> <local folder>
fetch() {
  local out="$3/$2"
  if pinned "$out" "$2"; then
    echo "have $out (checked)"
    return
  fi
  mkdir -p "$3"
  echo "downloading $2"
  curl -fL --proto '=https' --proto-redir '=https' --retry 3 -o "$out.part" "$ARCHIVE/$1/$2"
  if ! pinned "$out.part" "$2"; then
    echo "$2: the download's SHA-256 is not the pinned one; nothing kept, nothing pushed" >&2
    rm -f "$out.part"
    exit 1
  fi
  mv "$out.part" "$out"
}

# checked <local file> <archive.org name>: stops the script unless the file still has its pin, right before a push.
checked() {
  if ! pinned "$1" "$2"; then
    echo "$1: its SHA-256 is not the pinned one; nothing pushed. Run this script again to fetch it again." >&2
    exit 1
  fi
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

# push <phone folder> <local file>...: each LibriVox file checked against its pin first (the fixtures are the repo's).
push() {
  local dir="$1" file
  shift
  for file in "$@"; do
    if [ -n "${SHA256[$(basename "$file")]:-}" ]; then checked "$file" "$(basename "$file")"; fi
  done
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
  checked "$cache/Aesop 3/$AESOP_M4B" "$AESOP_M4B"
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
