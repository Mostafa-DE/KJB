# KJB release shrinking rules.
#
# The activity and the notification listener are referenced from the manifest, which AGP
# keeps automatically. kotlinx.serialization, DataStore, coroutines and Compose ship their
# own consumer rules. The AI (ONNX Runtime and its reflection) is in the AI add-on, with its
# own rules (ai-addon/proguard-rules.pro).
#
# Add -keep rules here when a change loads classes by name.

# The home-screen widgets' buttons (widget/Widgets.kt): Glance makes each ActionCallback by its name with
# getDeclaredConstructor().newInstance(), which R8 cannot see. Glance's own rule keeps the classes' names only, so
# without this R8 removed the constructors and every widget tap failed (3.0.2 to 3.0.4). assembleRelease checks the
# APK for them (checkReleaseWidgetCallbacks in app/build.gradle.kts).
-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }
