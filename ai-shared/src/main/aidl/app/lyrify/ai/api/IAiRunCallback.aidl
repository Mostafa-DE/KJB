// What one song's job tells KJB (IAiAddon.run), in order: oneway calls to one binder arrive in the order they were sent.
package app.lyrify.ai.api;

oneway interface IAiRunCallback {
    // The job enters a stage (an AiStage name): KJB's crash guard keeps it.
    void onStage(long job, String stage) = 1;

    // One line for KJB's AI run log: stages, times, counts. Never lyrics, never a path.
    void onNote(long job, String line) = 2;

    // The next Whisper chunk waits for IAiAddon.proceed (KJB holds it while the phone is too warm).
    void onChunk(long job) = 3;

    // The job's end: its AiResult as JSON; null when longer than Wire.RESULT_PART_CHARS, for a oneway call: KJB reads it
    // with IAiAddon.result. The add-on keeps it either way, for a callback that is lost.
    void onResult(long job, String result) = 4;

    // The job's end after IAiAddon.cancel: nothing to record.
    void onStopped(long job) = 5;

    // A piece of the stage the job is in is done (an MDX segment, a wav2vec2 window, an aligner block): the first of each
    // stage at once, then at most every few seconds. KJB's watchdog hears the job move through its long stages.
    void onProgress(long job, int done, int total) = 6;
}
