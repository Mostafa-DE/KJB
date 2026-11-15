// What one download run tells KJB (IDlAddon.start), in order: oneway calls to one binder arrive in the order they were sent.
package app.lyrify.dl.api;

oneway interface IDlRunCallback {
    // JSON DlProgress: the stage, and while downloading its percent, speed and time left. Every few hundred milliseconds
    // at most, and at each new line yt-dlp writes, so KJB's watchdog hears the run move.
    void onProgress(long run, String progress) = 1;

    // JSON DlFile: the finished file waits for IDlAddon.deliver.
    void onFile(long run, String file) = 2;

    // JSON DlEnd: delivered, failed, or stopped. The run's last call.
    void onEnd(long run, String end) = 3;

    // The run is alive: every few seconds until its end, from a timer of the add-on's own, whatever yt-dlp is doing. KJB
    // takes silence for a phone's app freezer at work.
    void onBeat(long run) = 4;
}
