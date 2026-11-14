# The KJB Downloads add-on's release shrinking rules (KJB's own are in app/proguard-rules.pro).
#
# The bound service is referenced from the manifest, which AGP keeps. kotlinx.serialization and
# coroutines ship their own consumer rules.
#
# youtubedl-android reads yt-dlp's update answers and video info with Jackson, by reflection
# (VideoInfo, its mapper classes), and uses commons-io and commons-compress; VidGrab's rules,
# which its release build ran with on the phone.
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class org.apache.commons.** { *; }
-dontwarn com.fasterxml.jackson.**
-dontwarn org.apache.commons.**
