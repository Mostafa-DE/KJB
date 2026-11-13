// The AI add-on's bound service (app.lyrify.ai.AiService), as KJB calls it (lyrify-bench handoff/ai-addon-design.md).
// Versioned: a method keeps its transaction code for good, new ones are only added at the end with VERSION raised, and
// KJB asks apiVersion() before anything else (app.lyrify.addon.AddonClient). Every call checks that the caller is KJB, by
// its uid and signing certificate. The strings carry JSON (app.lyrify.ai.api.Wire): AiResult, AddonStatus, ImportReply.
package app.lyrify.ai.api;

import app.lyrify.ai.api.IAiRunCallback;
import app.lyrify.ai.api.IAiStatusCallback;

interface IAiAddon {
    const int VERSION = 1;

    // VERSION of the add-on that answers.
    int apiVersion() = 1;

    // The add-on now, as JSON AddonStatus.
    String status() = 2;

    // The callback hears the status now and at each change, until unwatchStatus or until KJB goes away.
    void watchStatus(IAiStatusCallback callback) = 3;
    void unwatchStatus(IAiStatusCallback callback) = 4;

    // Starts the model download (Wi-Fi only). With restart, one under way starts anew from its parts; without, it is kept.
    void startDownload(boolean restart) = 5;

    // Stops the model download; what it got so far stays for the next one.
    void cancelDownload() = 6;

    // Stops a download and deletes the models and their parts, after closing the engine. True when none is left.
    boolean deleteModels() = 7;

    // Deletes one model that did not load (an AiModels name), so that the download fetches it again.
    boolean discardModel(String name) = 8;

    // Starts one song and returns its job at once; the callback hears its stages, notes and chunks, then its end. The
    // song is read from the descriptor only; the add-on never learns its path.
    long run(in ParcelFileDescriptor song, long durationMs, String plainLyrics, IAiRunCallback callback) = 9;

    // After callback.onChunk: the next Whisper chunk may start.
    void proceed(long job) = 10;

    // Stops the job after the step it is on; the callback hears onStopped.
    void cancel(long job) = 11;

    // Releases the NPU sessions: the end of a night's run.
    void closeEngine() = 12;

    // The handover of KJB 2.0's models: one file under its name in the models folder, with its size and SHA-256. JSON
    // ImportReply; a file the add-on already holds as given is not read again.
    String importModel(String name, in ParcelFileDescriptor file, long bytes, String sha256) = 13;
}
