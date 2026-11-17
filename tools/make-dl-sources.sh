#!/usr/bin/env bash
# Builds the source bundle that goes with every KJB release carrying the Downloads add-on (KJB Downloads,
# app.lyrify.dl), so that whoever gets the add-on can get the complete corresponding source of its GPL, LGPL and MPL
# parts (GNU GPL 3.0 section 6, LGPL 2.1 section 6, MPL 2.0 section 3.2). Attach its output to the GitHub release by
# hand; never commit it.
#
#   tools/make-dl-sources.sh VERSION OUT_DIR [CACHE_DIR]
#
# VERSION is the add-on's versionName (dl-addon/build.gradle.kts); OUT_DIR gets KJB-Downloads-<VERSION>-sources.tar.gz.
# CACHE_DIR (default "${XDG_CACHE_HOME:-$HOME/.cache}/kjb-dl-sources") keeps the downloads between runs; a cached file
# is used only while its SHA-256 is still the pinned one.
#
# The add-on carries youtubedl-android 0.18.1 from Maven Central, and with it the Termux project's builds of Python
# (libpython.zip.so) and FFmpeg (libffmpeg.zip.so) and of the libraries they use. The bundle holds:
# 1. youtubedl-android's tree at its tag 0.18.1 (YDLA_COMMIT), from github.com/yausername/youtubedl-android, which its
#    POM names and where 0.18.1 is tagged (JunkFood02's fork, whose Maven group it is published under, has no such tag),
#    checked out by git at the pinned commit, without the prebuilt binaries (the jniLibs folders, the yt-dlp zipapp in
#    res/raw and the Gradle wrapper jar); and the three -sources.jar files Maven Central publishes beside its AARs.
# 2. The source release of each GPL, LGPL and MPL project in the two zips, at the version in the binaries, as the
#    Termux recipe at TERMUX_COMMIT downloads it, checked against the SHA-256 that recipe pins (UPSTREAM below).
#    Python's is there too: its readline and _gdbm modules link GNU Readline and GNU dbm (GPL 3.0 or later).
# 3. termux-packages at TERMUX_COMMIT: the recipe and patches of every package in the two zips (TERMUX_PACKAGES), with
#    Termux's build system (build-package.sh, scripts/, ndk-patches/). youtubedl-android records no commit; README.txt
#    says how this one was chosen.
# 4. README.txt (written below) and SHA256SUMS, the SHA-256 of every other file in the bundle.
#
# Every download is over HTTPS only and kept only once its SHA-256 (or, for the two git trees, its commit id) is the
# pinned one; anything else stops the script with nothing written. Nothing downloaded is run. The tar is reproducible
# (names sorted, owner 0, the youtubedl-android commit's time on every file, fixed modes, gzip -n), so the same VERSION
# gives the same bytes. Needs bash 4, curl, git 2.27 or later, GNU tar, gzip and sha256sum.
set -euo pipefail

YDLA_URL="https://github.com/yausername/youtubedl-android"
YDLA_TAG="0.18.1"
YDLA_COMMIT="d725d5c9a18c3a99a13ee0308bf78275dc310760"
MAVEN="https://repo1.maven.org/maven2/io/github/junkfood02/youtubedl-android"
TERMUX_URL="https://github.com/termux/termux-packages"
TERMUX_COMMIT="efe21539bdf6f220be35d9d944a000e764f4ccf8"

# The -sources.jar of each youtubedl-android module on Maven Central: file and SHA-256 (taken on 2026-11-17 from files
# whose SHA-1 matched Maven Central's own .sha1).
MAVEN_JARS=(
  "library-0.18.1-sources.jar 26e94f33de0ba802f3507862d509f80d100c1c9065ded7556d16ff51249c4a9a"
  "ffmpeg-0.18.1-sources.jar fb95701c2697a501ebaf39c02112c58983dc3b855a4d957d825b7c6942b7afec"
  "common-0.18.1-sources.jar 7db17b16e5a66572aa40c8866941ae11f3f49128849beb2dc004dac84ca06a9b"
)

# Each project's source: file, address and SHA-256, as the Termux recipe at TERMUX_COMMIT names them (Mutagen's from
# PyPI). v4l-utils comes from Debian's snapshot archive, byte for byte the upstream file (the same SHA-256, and
# upstream's signature verifies): linuxtv.org answers scripts with a bot check page.
UPSTREAM=(
  "ffmpeg-7.1.1.tar.xz https://www.ffmpeg.org/releases/ffmpeg-7.1.1.tar.xz 733984395e0dbbe5c046abda2dc49a5544e7e0e1e2366bba849222ae9e3a03b1"
  "x264-4613ac3c15fd75cebc4b9f65b7fb95e70a3acce1.tar.bz2 https://code.videolan.org/videolan/x264/-/archive/4613ac3c15fd75cebc4b9f65b7fb95e70a3acce1/x264-4613ac3c15fd75cebc4b9f65b7fb95e70a3acce1.tar.bz2 2a1b197fd1fbc85045794f18c9353648a9ae3cbe194b7b92d523d096f9445464"
  "x265_4.1.tar.gz https://bitbucket.org/multicoreware/x265_git/downloads/x265_4.1.tar.gz a31699c6a89806b74b0151e5e6a7df65de4b49050482fe5ebf8a4379d7af8f29"
  "xvidcore-1.3.7.tar.gz https://downloads.xvid.com/downloads/xvidcore-1.3.7.tar.gz abbdcbd39555691dd1c9b4d08f0a031376a3b211652c0d8b3b8aa9be1303ce2d"
  "vid.stab-1.1.1.tar.gz https://github.com/georgmartius/vid.stab/archive/refs/tags/v1.1.1.tar.gz 9001b6df73933555e56deac19a0f225aae152abbc0e97dc70034814a1943f3d4"
  "rubberband-4.0.0.tar.bz2 https://breakfastquay.com/files/releases/rubberband-4.0.0.tar.bz2 af050313ee63bc18b35b2e064e5dce05b276aaf6d1aa2b8a82ced1fe2f8028e9"
  "fftw-3.3.10.tar.gz https://www.fftw.org/fftw-3.3.10.tar.gz 56c932549852cddcfafdab3820b0200c7742675be92179e59e6215b340e26467"
  "readline-8.3.tar.gz https://mirrors.kernel.org/gnu/readline/readline-8.3.tar.gz fe5383204467828cd495ee8d1d3c037a7eba1389c22bc6a041f627976f9061cc"
  "readline83-001 https://mirrors.kernel.org/gnu/readline/readline-8.3-patches/readline83-001 21f0a03106dbe697337cd25c70eb0edbaa2bdb6d595b45f83285cdd35bac84de"
  "gdbm-1.26.tar.gz https://mirrors.kernel.org/gnu/gdbm/gdbm-1.26.tar.gz 6a24504a14de4a744103dcb936be976df6fbe88ccff26065e54c1c47946f4a5e"
  "gnutls-3.8.10.tar.xz https://www.gnupg.org/ftp/gcrypt/gnutls/v3.8/gnutls-3.8.10.tar.xz db7fab7cce791e7727ebbef2334301c821d79a550ec55c9ef096b610b03eb6b7"
  "nettle-3.10.2.tar.gz https://mirrors.kernel.org/gnu/nettle/nettle-3.10.2.tar.gz fe9ff51cb1f2abb5e65a6b8c10a92da0ab5ab6eaf26e7fc2b675c45f1fb519b5"
  "gmp-6.3.0.tar.xz https://mirrors.kernel.org/gnu/gmp/gmp-6.3.0.tar.xz a3c2b80201b89e68616f4ad30bc66aee4927c3ce50e33929ca819d5c43538898"
  "libidn2-2.3.8.tar.gz https://mirrors.kernel.org/gnu/libidn/libidn2-2.3.8.tar.gz f557911bf6171621e1f72ff35f5b1825bb35b52ed45325dcdee931e5d3c0787a"
  "libunistring-1.3.tar.xz https://mirrors.kernel.org/gnu/libunistring/libunistring-1.3.tar.xz f245786c831d25150f3dfb4317cda1acc5e3f79a5da4ad073ddca58886569527"
  "libiconv-1.18.tar.gz https://mirrors.kernel.org/gnu/libiconv/libiconv-1.18.tar.gz 3b08f5f4f9b4eb82f151a7040bfd6fe6c6fb922efe4b1659c66ea933276965e8"
  "glib-2.86.0.tar.xz https://download.gnome.org/sources/glib/2.86/glib-2.86.0.tar.xz b5739972d737cfb0d6fd1e7f163dfe650e2e03740bb3b8d408e4d1faea580d6d"
  "fribidi-1.0.16.tar.xz https://github.com/fribidi/fribidi/releases/download/v1.0.16/fribidi-1.0.16.tar.xz 1b1cde5b235d40479e91be2f0e88a309e3214c8ab470ec8a2744d82a5a9ea05c"
  "libbluray-1.3.4.tar.bz2 https://downloads.videolan.org/pub/videolan/libbluray/1.3.4/libbluray-1.3.4.tar.bz2 478ffd68a0f5dde8ef6ca989b7f035b5a0a22c599142e5cd3ff7b03bbebe5f2b"
  "libudfread-1.1.2.tar.gz https://code.videolan.org/videolan/libudfread/-/archive/1.1.2/libudfread-1.1.2.tar.gz 2bf16726ac98d093156195bb049a663e07d3323e079c26912546f4e05c77bac5"
  "libgme-0.6.4-src.tar.gz https://github.com/libgme/game-music-emu/releases/download/0.6.4/libgme-0.6.4-src.tar.gz 6f94eac735d86bca998a7ce1170d007995191ef6d4388345a0dc5ffa1de0bafa"
  "lame-3.100.tar.gz https://downloads.sourceforge.net/project/lame/lame/3.100/lame-3.100.tar.gz ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e"
  "mpg123-1.33.2.tar.bz2 https://downloads.sourceforge.net/sourceforge/mpg123/mpg123-1.33.2.tar.bz2 2c54fabbfa696dce8f9b137c8ef7a429a061f8fe633cd7d0a511809855f2c219"
  "soxr-0.1.3-Source.tar.xz https://downloads.sourceforge.net/project/soxr/soxr-0.1.3-Source.tar.xz b111c15fdc8c029989330ff559184198c161100a59312f5dc19ddeb9b5a15889"
  "v4l-utils-1.24.1.tar.bz2 https://snapshot.debian.org/file/08f1d7cfa40c3eeaa47f3ca86306ff878d5767f9 cbb7fe8a6307f5ce533a05cded70bb93c3ba06395ab9b6d007eb53b75d805f5b"
  "libssh-0.11.3.tar.xz https://www.libssh.org/files/0.11/libssh-0.11.3.tar.xz 7d8a1361bb094ec3f511964e78a5a4dba689b5986e112afabe4f4d0d6c6125c3"
  "graphite-1.3.14.tar.gz https://github.com/silnrsi/graphite/archive/1.3.14.tar.gz 7a3b342c5681921ce2e0c2496509d30b5b078399d5a7bd2358f95166d57d91df"
  "srt-1.5.4.tar.gz https://github.com/Haivision/srt/archive/refs/tags/v1.5.4.tar.gz d0a8b600fe1b4eaaf6277530e3cfc8f15b8ce4035f16af4a5eb5d4b123640cdd"
  "zeromq-4.3.5.tar.gz https://github.com/zeromq/libzmq/releases/download/v4.3.5/zeromq-4.3.5.tar.gz 6653ef5910f17954861fe72332e68b03ca6e4d9c7160eb3a8de5a5a913bfab43"
  "Python-3.12.11.tar.xz https://www.python.org/ftp/python/3.12.11/Python-3.12.11.tar.xz c30bb24b7f1e9a19b11b55a546434f74e739bb4c271a3e3a80ff4380d49f7adb"
  "mutagen-1.47.0.tar.gz https://files.pythonhosted.org/packages/81/e6/64bc71b74eef4b68e61eb921dcf72dabd9e4ec4af1e11891bbd312ccbb77/mutagen-1.47.0.tar.gz 719fadef0a978c31b4cf3c956261b3c58b6948b32023078a2117b1de09f0fc99"
)

# Every Termux package with files in libpython.zip.so or libffmpeg.zip.so: its folder in termux-packages/packages.
TERMUX_PACKAGES=(
  brotli ca-certificates ffmpeg fftw fontconfig freetype fribidi game-music-emu gdbm glib harfbuzz libandroid-glob
  libandroid-posix-semaphore libandroid-stub libandroid-support libaom libass libbluray libbz2 libc++ libcrypt libdav1d
  libexpat libffi libgmp libgnutls libgraphite libiconv libidn2 liblzma libmp3lame libmpg123 libnettle libogg
  libopencore-amr libopenmpt libopus libpng librav1e libsamplerate libsodium libsoxr libsqlite libsrt libssh libtheora
  libudfread libunistring libv4l libvidstab libvmaf libvo-amrwbenc libvorbis libvpx libwebp libx264 libx265 libxml2
  libzimg libzmq littlecms ncurses ocl-icd openssl pcre2 python python-pycryptodomex quickjs readline rubberband
  svt-av1 xvidcore zlib
)

usage() {
  cat <<EOF
Usage: $(basename "$0") VERSION OUT_DIR [CACHE_DIR]

Builds OUT_DIR/KJB-Downloads-VERSION-sources.tar.gz, the source of the GPL, LGPL and MPL parts of
the Downloads add-on VERSION, for its KJB release. CACHE_DIR (default
\${XDG_CACHE_HOME:-\$HOME/.cache}/kjb-dl-sources) keeps the downloads between runs.
EOF
}

die() {
  echo "$*" >&2
  exit 1
}

# pinned <file> <sha256>: true when the file is there and has that SHA-256.
pinned() {
  [ -s "$1" ] && [ "$(sha256sum "$1" | cut -d ' ' -f 1)" = "$2" ]
}

# fetch <file> <url> <sha256> <folder>: the file in the folder, downloaded unless already there with its pin.
fetch() {
  local out="$4/$1"
  if pinned "$out" "$3"; then
    return
  fi
  mkdir -p "$4"
  echo "downloading $1"
  curl -fsSL --proto '=https' --proto-redir '=https' --retry 3 -o "$out.part" "$2"
  if ! pinned "$out.part" "$3"; then
    rm -f "$out.part"
    die "$1: the download's SHA-256 is not the pinned one ($3); nothing written"
  fi
  mv "$out.part" "$out"
}

# tree <url> <commit> <folder> <pattern>...: the repository at that commit in the folder, only the paths the
# gitignore-style patterns keep, fetched without history and without the blobs left out.
tree() {
  local url="$1" commit="$2" dir="$3"
  shift 3
  if [ ! -d "$dir/.git" ]; then
    git clone -q --filter=blob:none --no-checkout --depth 1 "$url" "$dir"
  fi
  if ! git -C "$dir" cat-file -e "$commit^{commit}" 2>/dev/null; then
    git -C "$dir" fetch -q --depth 1 origin "$commit"
  fi
  git -C "$dir" config core.sparseCheckout true
  git -C "$dir" config core.sparseCheckoutCone false
  printf '%s\n' "$@" > "$dir/.git/info/sparse-checkout"
  git -C "$dir" -c advice.detachedHead=false checkout -q --force --detach "$commit"
  git -C "$dir" sparse-checkout reapply
  git -C "$dir" clean -q -f -d -x
  [ "$(git -C "$dir" rev-parse HEAD)" = "$commit" ] || die "$url: HEAD is not $commit"
}

# copy_tree <git folder> <destination>: the checked-out files, without .git.
copy_tree() {
  mkdir -p "$2"
  tar -C "$1" --exclude=.git -cf - . | tar -C "$2" -xf -
}

case "${1:-}" in
  -h | --help) usage; exit 0 ;;
esac
[ $# -ge 2 ] && [ $# -le 3 ] || { usage >&2; exit 2; }
version="$1"
out_dir="$2"
cache="${3:-${XDG_CACHE_HOME:-$HOME/.cache}/kjb-dl-sources}"
[[ "$version" =~ ^[0-9]+(\.[0-9]+)*$ ]] || die "not a version: $version (say it as in dl-addon/build.gradle.kts, such as 3.0.3)"
mkdir -p "$out_dir" "$cache"
name="KJB-Downloads-$version-sources"
out="$(cd "$out_dir" && pwd)/$name.tar.gz"

stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT
root="$stage/$name"
mkdir -p "$root"

# 1. youtubedl-android: its tree at the tag, and Maven Central's -sources.jar files.
tree "$YDLA_URL" "$YDLA_COMMIT" "$cache/youtubedl-android" \
  '/*' '!/*/src/main/jniLibs/' '!/library/src/main/res/raw/ytdlp' '!/gradle/wrapper/gradle-wrapper.jar'
copy_tree "$cache/youtubedl-android" "$root/youtubedl-android-$YDLA_TAG"
for jar in "${MAVEN_JARS[@]}"; do
  read -r file sha <<< "$jar"
  fetch "$file" "$MAVEN/${file%%-*}/$YDLA_TAG/$file" "$sha" "$cache/maven"
  mkdir -p "$root/youtubedl-android-$YDLA_TAG-maven"
  cp "$cache/maven/$file" "$root/youtubedl-android-$YDLA_TAG-maven/"
done

# 2. Each project's source release.
mkdir -p "$root/upstream"
for entry in "${UPSTREAM[@]}"; do
  read -r file url sha <<< "$entry"
  fetch "$file" "$url" "$sha" "$cache/upstream"
  cp "$cache/upstream/$file" "$root/upstream/"
done

# 3. Termux's recipes and build system.
patterns=('/build-package.sh' '/LICENSE.md' '/scripts/' '/ndk-patches/')
for package in "${TERMUX_PACKAGES[@]}"; do
  patterns+=("/packages/$package/")
done
tree "$TERMUX_URL" "$TERMUX_COMMIT" "$cache/termux-packages" "${patterns[@]}"
for package in "${TERMUX_PACKAGES[@]}"; do
  [ -f "$cache/termux-packages/packages/$package/build.sh" ] || die "termux-packages $TERMUX_COMMIT has no packages/$package"
done
copy_tree "$cache/termux-packages" "$root/termux-packages"

# 4. README.txt and SHA256SUMS.
sed "s/@VERSION@/$version/g" > "$root/README.txt" <<'EOF'
KJB Downloads @VERSION@: the source of the free software it carries

This bundle goes with KJB Downloads @VERSION@ (app.lyrify.dl), the Downloads add-on of KJB, and is attached to each
KJB release that ships that add-on, at https://github.com/Mostafa-DE/KJB/releases. It holds the complete
corresponding source of the add-on's parts under the GNU GPL, the GNU LGPL and the Mozilla Public License:
youtubedl-android 0.18.1 and the programs and libraries it carries, which are the Termux project's builds for
Android. KJB's own code in the add-on (dl-addon and addon-shared) is in https://github.com/Mostafa-DE/KJB, at the tag
of this release. The add-on's NOTICE.txt (assets/licences in its APK, and Settings > About > Licences in KJB) lists
every part with its licence and copyright; the parts under BSD, MIT and other permissive licences have no source here.

tools/make-dl-sources.sh in KJB's repository made this bundle; run with the same version it gives the same bytes.
SHA256SUMS lists the SHA-256 of every other file in it.


Contents

youtubedl-android-0.18.1/
  youtubedl-android at its tag 0.18.1, commit d725d5c9a18c3a99a13ee0308bf78275dc310760 of
  https://github.com/yausername/youtubedl-android (the repository its POM names, where 0.18.1 is tagged), without
  the prebuilt binaries in its tree: the */src/main/jniLibs folders (the very zips and programs the add-on carries,
  whose source is below), library/src/main/res/raw/ytdlp (a yt-dlp release, public domain, which the add-on replaces
  with yt-dlp's own updates) and gradle/wrapper/gradle-wrapper.jar. The add-on uses it as published on Maven Central:
  io.github.junkfood02.youtubedl-android, modules library, ffmpeg and common, 0.18.1.
youtubedl-android-0.18.1-maven/
  library-0.18.1-sources.jar, ffmpeg-0.18.1-sources.jar and common-0.18.1-sources.jar: the source Maven Central
  publishes beside those modules (https://repo1.maven.org/maven2/io/github/junkfood02/youtubedl-android/).
upstream/
  Each project's own source release, as published, at the version in the add-on's binaries (the list below).
termux-packages/
  https://github.com/termux/termux-packages at commit efe21539bdf6f220be35d9d944a000e764f4ccf8: the recipe
  (packages/<name>/build.sh) and patches of every package in the two zips, with Termux's build system
  (build-package.sh, scripts/, ndk-patches/). LICENSE.md says how Termux licenses them.


How the add-on's native files map to this source

The add-on's lib/arm64-v8a holds, from youtubedl-android:
- libffmpeg.so and libffprobe.so: the ffmpeg and ffprobe programs of FFmpeg.
- libffmpeg.zip.so: a zip of usr/lib from Termux's FFmpeg package and the packages it depends on.
- libpython.so: the python program. libpython.zip.so: a zip of usr/lib and usr/etc from Termux's Python package and
  the packages it depends on, with Mutagen and pycryptodomex in lib/python3.12/site-packages.
- libqjs.so: QuickJS's qjs program (MIT License).
youtubedl-android unpacks the zips on the phone and runs the programs. Its Java code is in the add-on's classes.dex.

Each GPL, LGPL or MPL part, its version, its licence, its files in the add-on and its source in upstream/ (each
built by its Termux recipe in termux-packages/packages/<name>):

In libffmpeg.zip.so, and libffmpeg.so and libffprobe.so
- FFmpeg 7.1.1 (ffmpeg): GPL-3.0-or-later, as configured with --enable-gpl --enable-version3 (the configure line is
  in libavutil). libavcodec, libavdevice, libavfilter, libavformat, libavutil, libpostproc, libswresample, libswscale.
  ffmpeg-7.1.1.tar.xz
- x264 0.164.3191 (libx264): GPL-2.0-or-later. libx264.so. x264-4613ac3c15fd75cebc4b9f65b7fb95e70a3acce1.tar.bz2 (that
  x264 commit, as Termux's recipe pins it)
- x265 4.1 (libx265): GPL-2.0-or-later. libx265.so. x265_4.1.tar.gz
- Xvid 1.3.7 (xvidcore): GPL-2.0-or-later. libxvidcore.so. xvidcore-1.3.7.tar.gz
- vid.stab 1.1.1 (libvidstab): GPL-2.0-or-later. libvidstab.so. vid.stab-1.1.1.tar.gz
- Rubber Band 4.0.0 (rubberband): GPL-2.0-or-later. librubberband.so, librubberband-jni.so. rubberband-4.0.0.tar.bz2
- FFTW 3.3.10 (fftw): GPL-2.0-or-later. libfftw3, libfftw3f, libfftw3l and their _threads libraries.
  fftw-3.3.10.tar.gz
- GnuTLS 3.8.10 (libgnutls): LGPL-2.1-or-later. libgnutls.so, libgnutls-dane.so, libgnutlsxx.so. gnutls-3.8.10.tar.xz
- Nettle 3.10.2 (libnettle): LGPL-3.0-or-later or GPL-2.0-or-later. libnettle.so, libhogweed.so.
  nettle-3.10.2.tar.gz
- GMP 6.3.0 (libgmp): LGPL-3.0-or-later or GPL-2.0-or-later. libgmp.so, libgmpxx.so. gmp-6.3.0.tar.xz
- libidn2 2.3.8 (libidn2): LGPL-3.0-or-later or GPL-2.0-or-later. libidn2.so. libidn2-2.3.8.tar.gz
- libunistring 1.3 (libunistring): LGPL-3.0-or-later or GPL-2.0-or-later. libunistring.so. libunistring-1.3.tar.xz
- GNU libiconv 1.18 (libiconv): LGPL-2.1-or-later. libiconv.so, libcharset.so. libiconv-1.18.tar.gz
- GLib 2.86.0 (glib): LGPL-2.1-or-later. libglib, libgio, libgobject, libgmodule, libgthread and libgirepository
  (-2.0.so), girepository-1.0/*.typelib, glib-2.0/include/glibconfig.h. glib-2.86.0.tar.xz
- GNU FriBidi 1.0.16 (fribidi): LGPL-2.1-or-later. libfribidi.so. fribidi-1.0.16.tar.xz
- libbluray 1.3.4 (libbluray): LGPL-2.1-or-later. libbluray.so. libbluray-1.3.4.tar.bz2
- libudfread 1.1.2 (libudfread): LGPL-2.1-or-later. libudfread.so. libudfread-1.1.2.tar.gz
- Game Music Emu 0.6.4 (game-music-emu): LGPL-2.1-or-later. libgme.so. libgme-0.6.4-src.tar.gz
- LAME 3.100 (libmp3lame): LGPL-2.0-or-later. libmp3lame.so. lame-3.100.tar.gz
- mpg123 1.33.2 (libmpg123): LGPL-2.1-only. libmpg123.so, libout123.so, libsyn123.so. mpg123-1.33.2.tar.bz2
- SoX Resampler 0.1.3 (libsoxr): LGPL-2.1-or-later. libsoxr.so, libsoxr-lsr.so. soxr-0.1.3-Source.tar.xz
- libv4l from v4l-utils 1.24.1 (libv4l): LGPL-2.1-or-later; its helper programs ov511-decomp and ov518-decomp are
  GPL-2.0-only. libv4l1.so, libv4l2.so, libv4lconvert.so, libv4l2rds.so, v4l1compat.so, v4l2convert.so and libv4l/.
  v4l-utils-1.24.1.tar.bz2
- libssh 0.11.3 (libssh): LGPL-2.1-or-later, parts BSD-2-Clause. libssh.so. libssh-0.11.3.tar.xz
- Graphite2 1.3.14 (libgraphite): LGPL-2.1-or-later or MPL-2.0 or GPL-2.0-or-later. libgraphite2.so.
  graphite-1.3.14.tar.gz
- SRT 1.5.4 (libsrt): MPL-2.0. libsrt.so. srt-1.5.4.tar.gz
- ZeroMQ 4.3.5 (libzmq): MPL-2.0. libzmq.so. zeromq-4.3.5.tar.gz

In libpython.zip.so
- Python 3.12.11 (python): PSF-2.0. libpython3.12.so, lib/python3.12 and the python program; its readline and _gdbm
  modules (lib/python3.12/lib-dynload) link the two libraries below, so its source is here too.
  Python-3.12.11.tar.xz
- GNU Readline 8.3 with its upstream patch 001 (readline): GPL-3.0-or-later. libreadline.so, libhistory.so,
  etc/inputrc. readline-8.3.tar.gz, readline83-001
- GNU dbm 1.26 (gdbm): GPL-3.0-or-later. libgdbm.so, libgdbm_compat.so. gdbm-1.26.tar.gz
- Mutagen 1.47.0: GPL-2.0-or-later. lib/python3.12/site-packages/mutagen, shipped as its Python source.
  mutagen-1.47.0.tar.gz (PyPI)

The CA certificates (etc/tls/cert.pem, MPL-2.0) ship in their own source form, Mozilla's root store as
https://curl.se/docs/caextract.html publishes it (data as of 2025-09-09); termux-packages/packages/ca-certificates
fetches it.


How the termux-packages commit was chosen

youtubedl-android records no termux-packages commit: its BUILD_FFMPEG.md and BUILD_PYTHON.md say to clone
termux-packages and build, and the binaries carry Termux's own prefix (/data/data/com.termux/files/usr) and NDK r28c,
so they are the packages Termux's own builders made. youtubedl-android committed them on 2025-09-27 (f2280ae "Bump
ffmpeg 7.1.1" and ed17169 "Bump python 3.12.11"; 4e2bb8b on 2025-11-07 only added QuickJS to the Python zip, whose
other files keep their September dates). The commit here, efe21539, is the last termux-packages commit before
f2280ae (2025-09-27 06:20 UTC, against 08:23 UTC).

It is the best match, not a recorded fact. Every version that can be read from the binaries is the version of the
recipe at efe21539, and two of them bound the date: HarfBuzz 11.5.1 was in the recipe only from 2025-09-22 to
2025-09-28, and Expat 2.7.3 (in the Python zip) from 2025-09-25. The files in each zip carry the time Termux built
them, and for every package with source in upstream/ that time is within hours of the last change to its recipe
before efe21539, so these are the recipes those packages were built from, revisions included. One recipe changed
after its build without a rebuild: libiconv's, on 2025-09-15, only its download address (to mirrors.kernel.org, the
same archive with the same SHA-256, marked %ci:no-build). Among the packages with no source here: libwebp's files are
from its 1.6.0-rc1 build of 2025-07-02, older than its recipe here (1.6.0, rebuilt on 2025-09-02); libzimg's recipe
later changed only its update check; libc++ and libandroid-stub (NDK r28c) are dated 2025-07-09, a month before
their recipes' last change reached termux-packages (2025-08-04).

Versions that could not be read from the binaries, so taken from the recipes at efe21539: Game Music Emu 0.6.4,
libv4l 1.24.1, Rubber Band 4.0.0 and vid.stab 1.1.1 (source here), and FreeType 2.14.1, libffi 3.4.7, libogg 1.3.6,
OpenCORE AMR 0.1.6, libandroid-support 29 (no source here).


How each archive was checked

The script keeps an archive only when its SHA-256 is the one Termux's recipe pins (Mutagen's: the one PyPI lists).
When the script was written (2026-11-17), each was also checked against what its project publishes:
- its project's signature, which verified: FFmpeg (ffmpeg.org's release key), GNU Readline and its patch 001, GNU dbm,
  libidn2, Nettle, GMP, libunistring and libiconv (the GNU keyring), GnuTLS (gnutls.org's keyring), Python (its
  release manager's key), libssh, mpg123 and v4l-utils (the upstream keys Debian keeps for them). Nettle's, GMP's,
  libunistring's, libiconv's and GnuTLS's signing keys have expired since they signed.
- its project's published checksum, which matched: GLib (sha256sum), FFTW (md5sum), libbluray (sha512), LAME and SoX
  Resampler (SourceForge's md5), Mutagen (PyPI's sha256), and the three -sources.jar files (Maven Central's sha1).
- nothing published to check against, so Termux's pin only: x264 and libudfread (VideoLAN GitLab archives), x265
  (Bitbucket), Xvid, Rubber Band, vid.stab, Graphite2 and SRT (GitHub archives), FriBidi, Game Music Emu and ZeroMQ
  (GitHub release files).
youtubedl-android's tree and termux-packages are checked out by git at the commits named above.
EOF
(cd "$root" && find . -type f ! -name SHA256SUMS -print0 | LC_ALL=C sort -z | xargs -0 sha256sum) > "$stage/SHA256SUMS"
mv "$stage/SHA256SUMS" "$root/SHA256SUMS"

# The tar: sorted names, owner 0, modes and times fixed, so the same inputs give the same bytes.
epoch="$(git -C "$cache/youtubedl-android" log -1 --format=%ct "$YDLA_COMMIT")"
LC_ALL=C tar --sort=name --format=gnu --owner=0 --group=0 --numeric-owner --mtime="@$epoch" \
  --mode='u=rwX,go=rX' -C "$stage" -cf - "$name" | gzip -n -9 > "$out.part"
mv "$out.part" "$out"
echo "$out"
echo "size: $(du -h "$out" | cut -f 1) ($(stat -c %s "$out") bytes)"
echo "sha256: $(sha256sum "$out" | cut -d ' ' -f 1)"
