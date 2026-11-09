cmake_minimum_required(VERSION 3.22.1)

project(taglib)

# KJB patch: built with NDK r27.1, which does not align shared libraries to 16 KB pages by itself (r28 and later do), so
# the link sets max-page-size; taglib/build.gradle.kts fails the release build unless every LOAD segment of
# libtaglib.so is aligned to 16 KB (0x4000) or more.
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -fvisibility=hidden -flto -fdata-sections -ffunction-sections -fomit-frame-pointer")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -fvisibility=hidden -flto -fdata-sections -ffunction-sections -fomit-frame-pointer")
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -flto -Wl,--exclude-libs,ALL -Wl,--gc-sections -s -Wl,--pack-dyn-relocs=android -Wl,--build-id=none -Wl,-z,max-page-size=16384")
set(CMAKE_VISIBILITY_INLINES_HIDDEN YES)

set(VISIBILITY_HIDDEN ON)
set(BUILD_BINDINGS OFF)
set(BUILD_TESTING OFF)

# KJB patch: only the formats KJB changes (MP3, FLAC, M4A, Ogg Vorbis, Opus). APE stays on for the APE tags an MP3 may
# carry; Vorbis brings Ogg, FLAC and Opus.
set(WITH_ASF OFF)
set(WITH_DSF OFF)
set(WITH_MATROSKA OFF)
set(WITH_MOD OFF)
set(WITH_RIFF OFF)
set(WITH_SHORTEN OFF)
set(WITH_TRUEAUDIO OFF)

add_subdirectory(taglib)

include_directories(
        taglib/taglib
        taglib/taglib/toolkit
        taglib/taglib/mpeg
        taglib/taglib/ogg
        taglib/taglib/ogg/flac
        taglib/taglib/flac
        taglib/taglib/mp4
        taglib/taglib/ogg/vorbis
        taglib/taglib/ogg/opus
        taglib/taglib/mpeg/id3v2
        taglib/taglib/mpeg/id3v2/frames
        taglib/taglib/mpeg/id3v1
        taglib/taglib/ape
        taglib/taglib/wavpack)

add_library(${CMAKE_PROJECT_NAME} SHARED
        taglib.cpp
        fileref_ext.cpp)

target_link_libraries(${CMAKE_PROJECT_NAME}
        android
        tag)

add_custom_command(TARGET ${CMAKE_PROJECT_NAME} POST_BUILD
        COMMAND ${CMAKE_OBJCOPY}
        --remove-section .comment
        --remove-section .note
        --strip-debug $<TARGET_FILE:${CMAKE_PROJECT_NAME}>)
