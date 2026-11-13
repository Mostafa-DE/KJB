// The add-on's model download as it goes (IAiAddon.download): KJB's notification shows it.
package app.lyrify.ai.api;

oneway interface IAiDownloadCallback {
    // Bytes done of all the models, at most once per whole percent.
    void onProgress(long done, long total) = 1;

    // The download's end: its Download as JSON (done, stopped for now, or failed for good).
    void onEnd(String end) = 2;
}
