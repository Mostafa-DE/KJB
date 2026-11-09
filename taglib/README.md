# :taglib, vendored for KJB's tag writer

What is here, and where it came from:

- `src/main/java/com/kyant/taglib/`, `src/main/cpp/*.cpp`, `src/main/cpp/*.h`, `src/main/cpp/CMakeLists.txt`,
  `consumer-rules.pro`: the JNI wrapper of Kyant0/taglib 1.0.6 (https://github.com/Kyant0/taglib, commit 957fd7c),
  Apache License 2.0, Copyright Kyant. Its licence is `LICENSE`. Parts of `fileref_ext.*` are TagLib code (LGPL-2.1).
- `src/main/cpp/taglib/`: TagLib 2.3.2 (https://github.com/taglib/taglib, tag v2.3.2), without its tests, examples,
  bindings and docs. TagLib is offered under LGPL-2.1 or MPL-1.1; KJB takes it under the GNU LGPL 2.1
  (`src/main/cpp/taglib/COPYING.LGPL`), which is compatible with KJB's GPL-3.0. TagLib's own files are unchanged.
- `src/main/cpp/taglib/3rdparty/utfcpp/`: utfcpp 4.2.0 (https://github.com/nemtrif/utfcpp), TagLib's pinned
  submodule, Boost Software License 1.0 (`LICENSE` there), source only.

KJB's patches, each marked "KJB patch" in the code:

- `TagLib.saveTags`: one save for the property map and the pictures together. An MP3 has only its ID3v2 tag
  rewritten, as the ID3v2 version asked for (3 or 4), with `DoNotDuplicate`: no ID3v1 tag is added, and an existing
  one is left as it is. The descriptor is closed on every path.
- `utils.h`: a picture without data no longer leaves a null at the end of the picture array; picture strings are
  read as UTF-8 and released, and local references are freed in the loop.
- `CMakeLists.txt`: CMake 3.22.1, `-Wl,-z,max-page-size=16384` (NDK r27.1 does not align to 16 KB pages by itself),
  and only the formats KJB changes (MP3, FLAC, M4A, Ogg Vorbis, Opus; APE for an MP3's APE tags). `fileref_ext.cpp`
  drops ASF, AIFF, WAV, DSF, DSDIFF and Matroska to match.
- `build.gradle.kts` (new): NDK 27.1.12297006, arm64-v8a only, and `checkReleaseLoadAlignment`, which fails every
  release build unless each LOAD segment of `libtaglib.so` is aligned to 16 KB or more (`llvm-readelf -l`).

TagLib only ever runs on KJB's app-private copies of a song (`app/.../tags/TagFile.kt`), never on the user's file.
