// The Downloads add-on's bound service (app.lyrify.dl.DlService), as KJB calls it (lyrify-bench handoff/downloads-addon-design.md).
// Versioned: KJB asks apiVersion() before anything else (app.lyrify.downloads.DlClient). Frozen from the first public
// release that ships it: from then on a method keeps its transaction code and signature for good, and new ones are only
// added at the end with VERSION raised. Every call checks that the caller is KJB, by its uid and signing certificate. The
// strings carry JSON (app.lyrify.dl.api.DlWire): DlStatus, DlInfo, DlRequest, DlProgress, DlFile, DlEnd, DlUpdate, and
// from VERSION 2 DlSearch, DlSearchResults and a lookup's DlPlaylist.
package app.lyrify.dl.api;

import app.lyrify.dl.api.IDlInfoCallback;
import app.lyrify.dl.api.IDlRunCallback;
import app.lyrify.dl.api.IDlSearchCallback;

interface IDlAddon {
    // 2: search, and a lookup's playlist (DlInfo.playlist). KJB asks search only of an add-on that answers 2 or more: an
    // older one would answer the call with nothing.
    const int VERSION = 2;

    // VERSION of the add-on that answers.
    int apiVersion() = 1;

    // The add-on now, as JSON DlStatus: yt-dlp's version and when it last looked for an update.
    String status() = 2;

    // Looks up a link and returns the lookup's id at once. The callback hears the quick info first when the site has it
    // (YouTube's oEmbed, a partial DlInfo), then the full info, or why it failed. A lookup stops when its caller goes away.
    // VERSION 2: a playlist's link (a list, an album, a Mix) has its songs listed in the full info's playlist, none of them
    // opened, and a song's link that names a playlist too has that playlist listed after the song.
    long info(String url, IDlInfoCallback callback) = 3;

    // Stops a lookup, or a search (VERSION 2): its yt-dlp process is killed.
    void cancelInfo(long lookup) = 4;

    // Starts a download (JSON DlRequest) and returns the run's id at once. The callback hears its progress, then the
    // finished file (onFile), which waits for deliver(); then the end. Before yt-dlp runs it updates itself when its last
    // update is more than a day old, and after a failure it updates and tries once more. Another run of the same key goes
    // on from what an earlier one left. A run stops when its caller goes away. A run is one video: a playlist's link
    // downloads nothing, as KJB saves a playlist song by song.
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

    // VERSION 2. Searches YouTube for a request's words (JSON DlSearch) and returns the search's id at once, as info does:
    // the callback hears the videos found (YouTube's list only, nothing downloaded), or why it failed. cancelInfo stops
    // it, and so does its caller going away.
    long search(String request, IDlSearchCallback callback) = 10;
}
