// The Downloads add-on's bound service (app.lyrify.dl.DlService), as KJB calls it (lyrify-bench handoff/downloads-addon-design.md).
// Versioned: KJB asks apiVersion() before anything else (app.lyrify.downloads.DlClient). Frozen from the first public
// release that ships it: from then on a method keeps its transaction code and signature for good, and new ones are only
// added at the end with VERSION raised. Every call checks that the caller is KJB, by its uid and signing certificate. The
// strings carry JSON (app.lyrify.dl.api.DlWire): DlStatus, DlInfo, DlRequest, DlProgress, DlFile, DlEnd, DlUpdate.
package app.lyrify.dl.api;

import app.lyrify.dl.api.IDlInfoCallback;
import app.lyrify.dl.api.IDlRunCallback;

interface IDlAddon {
    const int VERSION = 1;

    // VERSION of the add-on that answers.
    int apiVersion() = 1;

    // The add-on now, as JSON DlStatus: yt-dlp's version and when it last looked for an update.
    String status() = 2;

    // Looks up a link and returns the lookup's id at once. The callback hears the quick info first when the site has it
    // (YouTube's oEmbed, a partial DlInfo), then the full info, or why it failed. A lookup stops when its caller goes away.
    long info(String url, IDlInfoCallback callback) = 3;

    // Stops a lookup: its yt-dlp process is killed.
    void cancelInfo(long lookup) = 4;

    // Starts a download (JSON DlRequest) and returns the run's id at once. The callback hears its progress, then the
    // finished file (onFile), which waits for deliver(); then the end. Before yt-dlp runs it updates itself when its last
    // update is more than a day old, and after a failure it updates and tries once more. Another run of the same key goes
    // on from what an earlier one left. A run stops when its caller goes away.
    long start(String request, IDlRunCallback callback) = 5;

    // Streams the run's finished file into media, a descriptor KJB opened for writing, and its cover (when onFile named one)
    // into cover. The add-on never opens the user's storage itself. The callback hears the end: delivered, or why not.
    void deliver(long run, in ParcelFileDescriptor media, in ParcelFileDescriptor cover) = 6;

    // Stops a run: its yt-dlp and ffmpeg processes are killed. keepParts: what it got stays for a later run of its key
    // (KJB paused it), else it is deleted. The callback hears onEnd, stopped.
    void cancel(long run, boolean keepParts) = 7;

    // Deletes what the runs of a key left (a failed download the user removed from the list).
    void discard(String key) = 8;

    // Updates yt-dlp now and returns JSON DlUpdate. Takes seconds: KJB calls it off its main thread.
    String update() = 9;
}
