// The add-on's model download as it goes (IAiAddon.download): KJB's notification shows it.
package app.lyrify.ai.api;

oneway interface IAiDownloadCallback {
    // Bytes done of all the models, at most once per whole percent.
    void onProgress(long done, long total) = 1;

    // The download's end: its Download as JSON (done, stopped for now, or failed for good).
    void onEnd(String end) = 2;

    // The run is alive: every few seconds from its start to its end, from a timer of its own, whatever it waits on (a
    // slow server, the files of a run stopped a moment ago). KJB takes silence for a phone's app freezer at work.
    void onBeat() = 3;
}
