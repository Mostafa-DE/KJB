// What one song's job tells KJB (IAiAddon.run), in order: oneway calls to one binder arrive in the order they were sent.
package app.lyrify.ai.api;

oneway interface IAiRunCallback {
    // The job enters a stage (an AiStage name): KJB's crash guard keeps it.
    void onStage(long job, String stage) = 1;

    // One line for KJB's AI run log: stages, times, counts. Never lyrics, never a path.
    void onNote(long job, String line) = 2;

    // The next Whisper chunk waits for IAiAddon.proceed (KJB holds it while the phone is too warm).
    void onChunk(long job) = 3;

    // The job's end: its AiResult as JSON.
    void onResult(long job, String result) = 4;

    // The job's end after IAiAddon.cancel: nothing to record.
    void onStopped(long job) = 5;
}
