# benchmarkRelease only (app.lyrify.bench, the Baseline Profile's minified bench build): keeps the write-fence flag
# readable, so baselineprofile/ can check that this build lists and changes only Music/KJB test/ before it gives it All
# files access (Journeys.kt, fenced). Code reading the flag has it inlined, so release is unchanged.
-keep class app.lyrify.BuildConfig {
    public static final boolean TEST_FOLDER_ONLY;
}
