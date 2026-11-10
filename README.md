# KJB

KJB is a music player for the songs stored on your Android phone, with synced lyrics. It plays your own music files,
finds synced lyrics for them on [LRCLIB](https://lrclib.net) and saves each as a small `.lrc` file next to the song,
so any player that reads `.lrc` files shows them too. If you keep using another player, such as Samsung Music, KJB can
stay in the background and add lyrics for what that player plays.

KJB was called Lyrify until 2.0. Its package is still `app.lyrify`, so 3.0 installs over 2.x and keeps your lyrics
records, tones and AI models. It is a personal app, installed from the APK on the GitHub release page or with adb; it
is not in an app store.

## What it does

**Playing music**

- A library of everything on your phone: Songs, Albums, Artists, Folders and Playlists (and Genres, off by default),
  each sorted and filtered, with Play, Shuffle and Add all on every list. Favourite songs, albums, artists, folders
  and playlists.
- A Search tab over songs, albums, artists, folders, playlists and the words of your lyrics.
- Queues you can see: the queue playing, recent queues and saved ones. Play next, Add to queue, or start a new queue
  from any song or list.
- The player: the cover flips to the lyrics, and full-screen lyrics with their own text size. A sleep timer by time
  or by songs, speed and pitch, Repeat a part (A-B), bookmarks, and Play on (your phone's output picker).
- Sound: gapless playback, crossfade, volume levelling from ReplayGain tags, and an equalizer with a profile for each
  output (headphones, speaker, car).
- Headset buttons (one, two or three presses), lyrics on the lock screen, two home-screen widgets, Android Auto, and
  "Start when connected" for Bluetooth devices you pick (Android 13 and later).
- Your listening: what you played most and lately, kept on your phone.

**Lyrics**

- Synced lyrics from LRCLIB, looked up ahead of time for the songs coming up, so they are there when each song starts.
  Only lyrics timed for the same recording (within 3 seconds of its length) are written.
- Check these: when KJB is not sure it found the right song, you pick the lyrics, or search yourself.
- A synced lyrics editor: Make synced turns plain lyrics into synced ones, Edit timing fixes the times. Lyrics you made
  or edited are yours; KJB never replaces them.
- AI lyrics (optional, off by default): on phones with a supported Snapdragon chip, the phone's AI chip times plain
  lyrics or writes lyrics for songs no one has, overnight while the phone charges. See "Requirements".

**Your library**

- Covers & tags: KJB checks your albums against MusicBrainz and the Cover Art Archive when you ask, and shows what it
  would change. Nothing is written until you apply it.
- Edit tags for a song or many songs, Edit album, Rename artist and Merge artists, with suggestions for names that
  look like two spellings of one. Every edit is listed in History with Undo.
- Duplicates: finds songs stored twice. You pick which copies go to your phone's trash.
- Tones: makes a ringtone, alarm or notification sound from part of a song.
- Playlists to and from `.m3u` files, and backup and restore of your playlists, queues, favourites, plays, bookmarks,
  equalizer profiles, lyrics choices and settings.

## The promise

**KJB changes a song file only when you confirm, and only its tags and cover, never the music. You can undo every
change.**

- What it writes in your folders: `.lrc` lyrics next to your songs, tone files in Ringtones/, Alarms/ or
  Notifications/ when you save a tone, and the playlist and backup files you export, where you choose.
- It never overwrites a `.lrc` that was already there by itself. Edit timing may replace one, only after you tap Save,
  with a copy kept and Undo.
- It deletes only files it wrote itself: a `.lrc` KJB wrote while it is still byte for byte what KJB wrote (when you
  tap Remove lyrics), a half-written file of its own, and a tone KJB made when you delete it.
- A song leaves only when you move it to your phone's trash (from Duplicates or the song's sheet). KJB's dialog asks
  first, then your phone's own; you can restore it for about 30 days.

**What leaves your phone**

Only names and lengths leave your phone. Lyrics lookups send the title, artist, album and length to LRCLIB. Covers &
tags sends album titles, artists, song titles, track counts and lengths to MusicBrainz, and gets covers from the Cover
Art Archive and archive.org.

- The AI lyrics models download once, from Qualcomm AI Hub, GitHub and Hugging Face. The AI itself runs offline.
- No account, no ads, no analytics. ONNX Runtime's telemetry is switched off.
- Backups hold no songs, no `.lrc` files and no Bluetooth addresses.
- The app's log (logcat) holds counts, status codes and error types only: never titles, artists, paths, web addresses
  or lyrics. See "Logs".

## Permissions, and why

| Permission | Why | When |
| --- | --- | --- |
| All files access | To list your songs, save `.lrc` files next to them in any folder, and write tags you confirm | Required, at first run |
| Notification access ("See what's playing") | To read what another music app plays (its media session), for companion mode. KJB does not read your notifications. Writing tags also needs it, to check no other app is playing the song | Optional, at first run or in Settings |
| Unrestricted battery ("Keep running") | Helps on phones that stop apps in the background | Optional |
| Display over other apps | Only for "Show when locked": the lyrics open over the lock screen when the screen turns on. Nothing is drawn over other apps | Asked when you switch it on |
| Modify system settings | Only to set a tone as your ringtone, alarm or notification sound | Asked when you tap Set |
| Set alarms | Only so "Open Clock" can show your alarm list. KJB never sets or changes an alarm | Granted at install |
| Internet, network state | LRCLIB, Covers & tags and the one-time AI download (over Wi-Fi only) | Granted at install |
| Foreground services, wake lock | Playing music with the screen off; the overnight AI run and the model download | Granted at install |
| Companion device presence | "Start when connected": Android tells KJB when a Bluetooth device you picked connects | Granted at install; does nothing until you pick a device |
| Run at startup | WorkManager reschedules the AI run and the download after a restart | Granted at install |

The music notification needs no notification permission: media notifications are exempt.

## Install

1. Download `KJB-3.0.0.apk` from the release page on your phone.
2. Open it. If asked, allow your browser or file manager to install apps.
3. Open KJB and allow All files access. "See what's playing" and "Keep running" are optional.
4. If a switch is greyed out ("Restricted setting"), your phone blocks it for apps from outside an app store: in KJB tap
   **Greyed out?**, open App info, tap the three dots at the top right, then **Allow restricted settings**, and try again.

With adb instead: `adb install -r app/build/outputs/apk/release/KJB-3.0.0.apk`.

## Requirements

- KJB: Android 11 or newer, an arm64 phone.
- AI lyrics: Android 15 or newer and one of these Snapdragon chips. Other phones get everything except the AI.
  - Tested: 8 Elite Gen 5.
  - Should work: 8 Elite, 8 Gen 3, 8 Gen 1.
- The AI download is about 1 GB, once, over Wi-Fi, with about 2 GB free while it downloads.

## Using Android Auto

Android Auto shows apps from outside an app store only once you allow it:

1. On your phone, open Android Auto's settings (search for "Android Auto" in your phone's Settings).
2. Tap **Version**, then tap **Version and permission info** 10 times.
3. Tap **OK** on "Allow development settings?".
4. Open **Developer settings** from the three-dot menu and turn on **Unknown sources**.
5. Connect to your car again. KJB shows your recent queues, playlists, albums and artists, and takes voice requests
   ("Play ... on KJB").

KJB's Settings > About > Using Android Auto gives the same steps.

## Build

Requirements:

- JDK 17 or newer (tested with JDK 21);
- the Android SDK with platform `android-37.0` and build-tools 36.0.0;
- NDK `27.1.12297006` and CMake 3.22.1, for the TagLib module (`taglib/`, the tag writer's native library). Install
  both with the SDK Manager (SDK Tools > NDK (Side by side) and CMake).

```sh
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # if ANDROID_HOME is not set
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease assembleDebugAndroidTest
```

- The debug APK (`app/build/outputs/apk/debug/app-debug.apk`) is `app.lyrify.debug`, named "KJB dev". It installs
  beside the release app with its own data, and changes shared storage only in `Music/KJB test/`.
- The release APK is `app/build/outputs/apk/release/KJB-<version>.apk`, arm64-v8a only, about 80 MB (most of it the
  QNN runtime for the AI). It is signed when `~/.android-keys/lyrify-keystore.properties` (or the file named by
  `LYRIFY_KEYSTORE_PROPS`) gives `storeFile`, `storePassword`, `keyAlias` and `keyPassword`; without it the release
  build is unsigned. A release build fails if TagLib's library is not aligned to 16 KB pages.
- Device tests run on a connected phone, on the debug build: `./gradlew connectedDebugAndroidTest`.
- Unit test coverage: `./gradlew createDebugUnitTestCoverageReport`, then open
  `app/build/reports/coverage/test/debug/index.html`.

Toolchain: AGP 9.4.1, Gradle 9.8.2, Kotlin 2.4.21, Compose BOM 2026.09.00, Media3 1.11.1, Glance 1.2.0,
WorkManager 2.12.0, compileSdk 37, targetSdk 36, minSdk 30. Network requests use `HttpURLConnection`. The AI runs on
ONNX Runtime 1.30.0 with Qualcomm's QNN execution provider 2.6.0 and the QNN runtime 2.50.0.

## Logs

`adb logcat -s KJB` shows what KJB does, in counts, statuses and error types only, so a bug report never carries your
music. For more detail while debugging:

```sh
adb shell setprop log.tag.KJB VERBOSE   # on: each song's lyrics outcome by title, and stack traces
adb shell setprop log.tag.KJB ""        # off
```

The AI keeps its own run log in the app's files, shown in Tools > AI lyrics; it names the songs it worked on and never
leaves the phone.

## Licence and credits

KJB is free software under the GNU General Public License, version 3 (see `LICENSE`). Settings > About > Licences lists
everything the app is built with and shows each licence in full; `NOTICE` says the same in the repository. `LICENSE`
ends with this additional permission, which also opens KJB's own page in the app's Licences:

Additional permission under GNU GPL version 3 section 7

If you modify this Program, or any covered work, by linking or combining it with the Qualcomm AI Engine Direct (QNN)
runtime libraries (com.qualcomm.qti:qnn-runtime), or with the other libraries that the KJB app bundles under licences
other than the GNU GPL as listed in the NOTICE file (or a modified version of any of those libraries), containing parts
covered by the terms of the Qualcomm AI Stack License or of those other libraries' licences, the licensors of this
Program grant you additional permission to convey the resulting work.

- Lyrics come from [LRCLIB](https://lrclib.net), a free, community-built lyrics database. Lyrics belong to their
  rights holders.
- Covers and album details come from [MusicBrainz](https://musicbrainz.org) and the
  [Cover Art Archive](https://coverartarchive.org).
- Tags are read and written with [TagLib](https://taglib.org) (LGPL 2.1) through
  [Kyant0/taglib](https://github.com/Kyant0/taglib) (Apache 2.0).
- Playback is [Media3](https://github.com/androidx/media); the AI uses [ONNX Runtime](https://onnxruntime.ai), Qualcomm
  AI Hub's Whisper, UVR's MDX-Net and wav2vec2 models (see `NOTICE`).
- Icons: Material Symbols. Font: Bricolage Grotesque (SIL Open Font License 1.1).

Samsung and Samsung Music are trademarks of Samsung Electronics Co., Ltd. Snapdragon and Qualcomm are trademarks of
Qualcomm Incorporated. KJB is not affiliated with, endorsed by, or sponsored by Samsung or Qualcomm.
