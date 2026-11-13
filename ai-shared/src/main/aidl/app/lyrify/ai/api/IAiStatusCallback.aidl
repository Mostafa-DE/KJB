// The add-on's status as it changes (IAiAddon.watchStatus): JSON AddonStatus.
package app.lyrify.ai.api;

oneway interface IAiStatusCallback {
    void onStatus(String status) = 1;
}
