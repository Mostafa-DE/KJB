package app.lyrify.baselineprofile

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.boundsInScreen
import androidx.test.uiautomator.onElements
import androidx.test.uiautomator.scrollToElementOrNull
import androidx.test.uiautomator.textAsString

/*
 * KJB's main journeys, shared by the Baseline Profile generator and the benchmarks. They find their way by the words on
 * screen and what TalkBack reads, never by layout or test tags, so they keep working while screens change. A journey
 * whose screen is missing (a tab the user hid, no test folder) is skipped with a log line, never failed.
 *
 * The app is app.lyrify.bench: a release build with data of its own, beside the user's KJB. Unlike the debug build, its
 * changes to shared storage are not fenced to the test folder (data/SharedWrites.kt), and KJB looks up lyrics for its
 * own queue and writes `.lrc` files next to those songs. So these journeys never play the user's music: the only queue
 * they start is Music/KJB test/, the folder the debug build's device tests already write in.
 */

/** The app under test, as the Baseline Profile plugin passes it: :app's nonMinifiedRelease or benchmarkRelease build. */
val targetPackage: String
    get() = InstrumentationRegistry.getArguments().getString(TARGET_PACKAGE_ARGUMENT)
        ?: error("No $TARGET_PACKAGE_ARGUMENT argument: run these tests with the Gradle tasks in lyrify-bench/handoff/baseline-profile.md")

private const val TARGET_PACKAGE_ARGUMENT = "androidx.benchmark.targetPackageName"
private const val TAG = "KjbProfile"

// KJB's words, as app/src/main/res/values has them (KJB is English only): change them here when the copy changes.
private const val LIBRARY = "Library" // nav_library, the dock item
private const val SEARCH = "Search" // nav_search, the dock item
private const val SONGS = "Songs" // library_tab_songs
private const val ALBUMS = "Albums" // library_tab_albums
private const val PLAYLISTS = "Playlists" // library_tab_playlists
private val LIBRARY_TABS = listOf(SONGS, ALBUMS, "Artists", "Folders", PLAYLISTS, "Genres")
private const val CONTINUE = "Continue" // setup_continue, the first-run screen's last button
private const val PLAY = "Play" // lib_play, a list header's Play
private const val SHUFFLE = "Shuffle" // lib_shuffle
private const val PAUSE = "Pause" // player_pause
private const val OPEN_PLAYER = "Open the player" // nowbar_open_player, "Open the player, <title>, <artist>"
private const val CLOSE_PLAYER = "Close the player" // player_close

/** The debug build's test folder (SharedWrites.TEST_FOLDER), as Search's folder rows and the folder page show it. */
private const val TEST_FOLDER_NAME = "KJB test"
private const val TEST_FOLDER_PATH = "Music/KJB test"
private const val TEST_FOLDER_LINE = "Music › KJB test"

/** Search's first query: one letter, for a long list of results in every group. */
private const val ANY_WORDS = "a"

/**
 * Opens KJB on the Library, cold or warm. All files access comes first, as the user gives it in Settings: without it KJB
 * shows only its setup screen. The first time, the first-run screen is passed with Continue.
 */
fun MacrobenchmarkScope.startToLibrary() {
    allowAllFiles()
    pressHome()
    startActivityAndWait()
    val first = find(timeoutMs = 15_000) { label(LIBRARY) || label(CONTINUE) }
    if (first?.text == CONTINUE) passFirstRun()
    goTo(LIBRARY)
}

/** Library, Songs, flung down three times and back up: the longest list, where first-run jank shows most. */
fun MacrobenchmarkScope.scrollSongs() {
    if (!openSongs()) return
    flingSongs()
    mainList()?.fling(Direction.UP)
}

/** Shows Library, Songs; false when the tab cannot be found. */
fun MacrobenchmarkScope.openSongs(): Boolean = showTab(SONGS)

/** The Songs list flung down three times; the scroll benchmark measures only this. */
fun MacrobenchmarkScope.flingSongs() {
    val list = mainList() ?: return skipped("no song list")
    repeat(3) { list.fling(Direction.DOWN) }
    device.waitForIdle()
}

/** Library, Albums: the album card in view opened, its page flung, and Back. */
fun MacrobenchmarkScope.openAnAlbum() {
    if (!showTab(ALBUMS)) return
    val grid = mainList() ?: return skipped("no album grid")
    // The biggest thing to tap in the grid is an album card; the header's Play and Shuffle never count.
    val album = grid.onElements(timeoutMs = 3_000) { isClickable && isVisibleToUser && !label(PLAY) && !label(SHUFFLE) }
        .maxByOrNull { it.visibleBounds.width() * it.visibleBounds.height() }
        ?: return skipped("no album")
    album.click()
    // The album page has no tabs row; Back only once it shows, or Back would leave KJB.
    if (device.wait(Until.gone(By.text(ALBUMS)), 5_000) != true) return skipped("the album did not open")
    mainList()?.fling(Direction.DOWN)
    pressBack()
    device.waitForIdle()
}

/** Library, Playlists, flung; skipped when the user hides that tab. */
fun MacrobenchmarkScope.openPlaylists() {
    if (!showTab(PLAYLISTS)) return
    mainList()?.fling(Direction.DOWN)
    device.waitForIdle()
}

/** Search: a one-letter query typed and its results flung. */
fun MacrobenchmarkScope.search() {
    goTo(SEARCH)
    if (!typeInSearch(ANY_WORDS)) return
    hideKeyboard()
    mainList()?.fling(Direction.DOWN)
    device.waitForIdle()
}

/**
 * The player, opened from the Now bar and closed. It plays Music/KJB test/ only, found through Search, and pauses at once;
 * skipped on a phone without that folder or with nothing in it.
 */
fun MacrobenchmarkScope.openAndCloseThePlayer() {
    goTo(SEARCH)
    if (!typeInSearch(TEST_FOLDER_NAME)) return
    hideKeyboard()
    // A folder row's description ends with its path; a song row's never has one.
    val isTestFolder: AccessibilityNodeInfo.() -> Boolean = { isClickable && contentDescription?.contains(TEST_FOLDER_PATH) == true }
    val folder = find(timeoutMs = 3_000, block = isTestFolder)
        ?: mainList()?.scrollToElementOrNull(Direction.DOWN, timeoutMs = 3_000, block = isTestFolder)
        ?: return skipped("no $TEST_FOLDER_PATH folder")
    folder.click()
    // Play only once the folder's own page shows, so a Play on the screen before can never be the one tapped.
    find { textAsString()?.endsWith(TEST_FOLDER_LINE) == true } ?: return skipped("the $TEST_FOLDER_PATH page did not open")
    // The header's Play is the highest on screen. A short page is not scrollable, so not a mainList; the Now bar's Play
    // at the bottom can only resume this same folder, the one queue this app is ever given.
    val play = onAppElements(timeoutMs = 3_000) { label(PLAY) && isEnabled }.minByOrNull { it.visibleBounds.top }
        ?: return skipped("nothing to play in $TEST_FOLDER_PATH")
    play.click()
    try {
        // The Now bar, or the player itself if Play opens it.
        val shown = find { contentDescription?.startsWith(OPEN_PLAYER) == true || label(CLOSE_PLAYER) } ?: return skipped("no Now bar")
        if (shown.contentDescription?.startsWith(OPEN_PLAYER) == true) shown.click()
        find { label(CLOSE_PLAYER) } ?: return skipped("the player did not open")
        find(timeoutMs = 2_000) { label(PAUSE) }?.click()
        find { label(CLOSE_PLAYER) }?.click() ?: pressBack()
        device.waitForIdle()
    } finally {
        // Silent whatever happened above: the media key reaches the session playing, and pausing twice is harmless.
        device.executeShellCommand("input keyevent KEYCODE_MEDIA_PAUSE")
    }
}

/** All files access for the app under test, as the user gives it to KJB in Settings; the shell may set it. */
private fun MacrobenchmarkScope.allowAllFiles() {
    device.executeShellCommand("appops set --uid $packageName MANAGE_EXTERNAL_STORAGE allow")
}

/** The first-run screen: Continue ends a scrolling column and works once All files access is on. */
private fun MacrobenchmarkScope.passFirstRun() {
    mainList(timeoutMs = 2_000)?.let { column -> repeat(5) { if (!column.fling(Direction.DOWN)) return@let } }
    find { label(CONTINUE) }?.click() ?: return skipped("no Continue on the first-run screen")
    find(timeoutMs = 10_000) { label(LIBRARY) }
}

/** Taps a dock item, closing the keyboard first so the tap lands on KJB. */
private fun MacrobenchmarkScope.goTo(dockItem: String) {
    hideKeyboard()
    find { label(dockItem) && isClickable }?.click() ?: return skipped("no $dockItem in the dock")
    device.waitForIdle()
}

/** Shows a Library tab, scrolling the tabs row sideways when it is out of view; false when the tab is hidden. */
private fun MacrobenchmarkScope.showTab(tab: String): Boolean {
    val isTab: AccessibilityNodeInfo.() -> Boolean = { label(tab) && isClickable }
    val row = find(timeoutMs = 3_000) { isClickable && LIBRARY_TABS.any { label(it) } }?.scrollableParent()
    val button = find(timeoutMs = 0, block = isTab)
        ?: row?.scrollToElementOrNull(Direction.RIGHT, timeoutMs = 2_000, block = isTab)
        ?: row?.scrollToElementOrNull(Direction.LEFT, timeoutMs = 2_000, block = isTab)
        ?: return false.also { skipped("no $tab tab") }
    button.click()
    device.waitForIdle()
    return true
}

/** Puts [words] in the Search field; false when the screen has none. */
private fun MacrobenchmarkScope.typeInSearch(words: String): Boolean {
    val field = find { isEditable } ?: return false.also { skipped("no search field") }
    field.text = words
    device.waitForIdle()
    return true
}

/** Closes the keyboard when it shows: a swipe over it would type, and Back with none open would leave the screen. */
private fun MacrobenchmarkScope.hideKeyboard() {
    if (windows().any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }) {
        pressBack()
        device.waitForIdle()
    }
}

/**
 * The screen's main list: its tallest scrollable, at least a third of the screen high, so never the tabs row, and waited
 * for while the songs load. Flings keep clear of the system's edge gestures.
 */
private fun MacrobenchmarkScope.mainList(timeoutMs: Long = 5_000): UiObject2? {
    val tall = device.displayHeight / 3
    return onAppElements(timeoutMs) { isScrollable && boundsInScreen().height() > tall }
        .maxByOrNull { it.visibleBounds.height() }
        ?.apply { setGestureMargin(device.displayWidth / 5) }
}

/** The first node on screen in KJB's own window that matches [block]; other windows (the keyboard, the system) never count. */
private fun MacrobenchmarkScope.find(timeoutMs: Long = 5_000, block: AccessibilityNodeInfo.() -> Boolean): UiObject2? {
    val app = packageName
    return onElementOrNull(timeoutMs = timeoutMs) { this.packageName?.toString() == app && isVisibleToUser && block() }
}

/** Every node on screen in KJB's own window that matches [block]. */
private fun MacrobenchmarkScope.onAppElements(timeoutMs: Long, block: AccessibilityNodeInfo.() -> Boolean): List<UiObject2> {
    val app = packageName
    return onElements(timeoutMs = timeoutMs) { this.packageName?.toString() == app && isVisibleToUser && block() }
}

/** Whether this node shows [words] or TalkBack reads them: Compose puts a control's words in one or the other. */
private fun AccessibilityNodeInfo.label(words: String): Boolean = textAsString() == words || contentDescription?.toString() == words

private fun UiObject2.scrollableParent(): UiObject2? = generateSequence(parent) { it.parent }.firstOrNull { it.isScrollable }

private fun skipped(why: String) {
    Log.i(TAG, "journey skipped: $why")
}
