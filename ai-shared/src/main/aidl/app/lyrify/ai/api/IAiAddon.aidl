// The AI add-on's bound service (app.lyrify.ai.AiService), as KJB calls it (lyrify-bench handoff/ai-addon-design.md).
// Versioned: KJB asks apiVersion() before anything else (app.lyrify.addon.AddonClient). The wire is frozen from the
// first public release (KJB 3.0.0, VERSION 1): from then on a method keeps its transaction code and signature for good,
// and new ones are only added at the end, with VERSION raised when KJB must know whether the add-on has them (a oneway
// call an add-on lacks is dropped unanswered, so it needs no raise). Until that release, VERSION 1 is still being
// shaped. Every call checks that the caller is KJB, by its uid and signing certificate. The strings carry JSON
// (app.lyrify.ai.api.Wire): AiResult, AddonStatus, ImportReply.
package app.lyrify.ai.api;

import app.lyrify.ai.api.IAiDownloadCallback;
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

    // Downloads the models the add-on lacks, from the parts it has, and tells the callback its progress, its beats and its
    // end. KJB runs it from its own foreground service, which shows the notification, on the networks it allows, and then
    // starts the add-on's service for the foreground, as for a song's job. Returns the run's id. Each call is its own run,
    // heard by its own callback; runs take the files one after the other. A run stops when its caller goes away (its
    // parts stay).
    long download(IAiDownloadCallback callback) = 5;

    // Stops the model download run started by that download() call (its id), and no other; its parts stay.
    void cancelDownload(long run) = 6;

    // Stops a download and deletes the models and their parts, after closing the engine. True when none is left.
    boolean deleteModels() = 7;

    // Deletes one model that did not load (an AiModels name), so that the download fetches it again.
    boolean discardModel(String name) = 8;

    // Starts one song and returns its job at once; the callback hears its stages, notes, progress and chunks, then its
    // end. The song is read from the descriptor only; the add-on never learns its path.
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

    // The end of a job that finished with a result, kept by the add-on until the next job finishes: its AiResult as JSON,
    // part by part (Wire.RESULT_PART_CHARS each, "" past the end), for a result too long for onResult or whose callback
    // was lost. Null when the add-on keeps no result for that job (unknown, still running, stopped, or replaced).
    String result(long job, int part) = 14;

    // As cancelDownload, one way: the run (its id) KJB gave up on because the phone froze the add-on (no beat for 30 s).
    // KJB waits on nothing in a frozen process, and Android ends a frozen app that gets a call it must answer
    // (CachedAppOptimizer: "received sync transactions while frozen, killing"); a one-way call waits in the binder until
    // the add-on runs again. The run then stops, its parts stay, and the add-on leaves the foreground unless other work
    // runs, even while KJB's AI page still binds it (Android then keeps its service, and onUnbind never comes). Added
    // after VERSION 1 shipped, without raising it: an add-on that lacks it drops the call unanswered.
    oneway void abandonDownload(long run) = 15;
}
