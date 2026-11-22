// A YouTube search's answer (IDlAddon.search, VERSION 2): one call, its last.
package app.lyrify.dl.api;

oneway interface IDlSearchCallback {
    // JSON DlSearchResults: the videos found, in YouTube's order; none found is an answer too.
    void onResults(long search, String results) = 1;

    // The search's end without results: JSON DlEnd, failed.
    void onFailed(long search, String end) = 2;
}
