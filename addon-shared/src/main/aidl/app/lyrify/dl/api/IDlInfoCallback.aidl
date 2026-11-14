// A link's lookup as it goes (IDlAddon.info): oneway calls to one binder arrive in the order they were sent.
package app.lyrify.dl.api;

oneway interface IDlInfoCallback {
    // JSON DlInfo: the quick, partial one first when the site has one, then the full one, which ends the lookup.
    void onInfo(long lookup, String info) = 1;

    // The lookup's end without full info: JSON DlEnd, failed.
    void onFailed(long lookup, String end) = 2;
}
