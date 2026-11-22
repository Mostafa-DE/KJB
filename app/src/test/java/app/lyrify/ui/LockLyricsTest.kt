package app.lyrify.ui

import app.lyrify.R
import app.lyrify.books.skipsFor
import app.lyrify.library.SongRef
import app.lyrify.player.Queue
import app.lyrify.player.QueueItem
import app.lyrify.player.QueueSource
import app.lyrify.player.SourceKind
import app.lyrify.settings.PlaybackSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Lyrics on the lock screen: when they open, from the notification and by themselves, the switch's permission, and the
 * progress bar that seeks.
 */
class LockLyricsTest {
    @Test
    fun `a tap on the music notification shows the lyrics over the lock screen, and the app once unlocked`() {
        assertEquals(LockEntry.LYRICS, lockEntry(LockOrigin.NOTIFICATION, locked = true, loaded = true))
        assertEquals(LockEntry.APP, lockEntry(LockOrigin.NOTIFICATION, locked = false, loaded = true))
        assertEquals(LockEntry.APP, lockEntry(LockOrigin.NOTIFICATION, locked = false, loaded = false))
        // Never when nothing plays: the lock screen stays as it is.
        assertEquals(LockEntry.NOTHING, lockEntry(LockOrigin.NOTIFICATION, locked = true, loaded = false))
    }

    @Test
    fun `over the lock screen it waits for the queues to be read, also after a restart of the app`() {
        assertEquals(LockEntry.WAIT, lockEntry(LockOrigin.NOTIFICATION, locked = true, loaded = null))
        assertEquals(LockEntry.WAIT, lockEntry(LockOrigin.SCREEN_ON, locked = true, loaded = null))
        assertEquals(LockEntry.APP, lockEntry(LockOrigin.NOTIFICATION, locked = false, loaded = null))
        assertEquals(LockEntry.NOTHING, lockEntry(LockOrigin.SCREEN_ON, locked = false, loaded = null))
    }

    @Test
    fun `a tap on the notification opens the player through its route, the lyrics on top, in KJB's own task`() {
        // Unit tests run in the module's folder.
        val service = File("src/main/java/app/lyrify/player/PlaybackService.kt").readText()
        assertTrue(Regex("""PendingIntent\.getActivities\(\s*this,\s*OPEN_PLAYER,\s*arrayOf\(openPlayer\(\), Intent\(this, LockLyricsActivity::class\.java\)\)""").containsMatchIn(service))
        assertTrue(service.contains(".putExtra(EXTRA_DESTINATION, DESTINATION_PLAYER)"))
        // No trampoline: the lock screen lyrics never start another screen.
        val lock = File("src/main/java/app/lyrify/ui/LockLyricsActivity.kt").readText()
        assertFalse(lock.contains("startActivity(Intent(this,"))
        // Its own document task when the screen turning on opens it, removed when it closes; no task of its own otherwise.
        assertTrue(lock.contains("Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK"))
        assertTrue(lock.contains("finishAndRemoveTask()"))
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val activity = Regex("""<activity\s+android:name="\.ui\.LockLyricsActivity"[^>]*>""").find(manifest)!!.value
        assertFalse(activity.contains("taskAffinity"))
        assertFalse(activity.contains("singleInstance"))
        assertTrue(activity.contains("android:showWhenLocked=\"true\""))
    }

    @Test
    fun `Show when locked turns itself off once Display over other apps is taken back`() {
        assertTrue(showWhenLockedNow(setting = true, overlayAllowed = true))
        assertFalse(showWhenLockedNow(setting = true, overlayAllowed = false))
        assertFalse(showWhenLockedNow(setting = false, overlayAllowed = true))
    }

    @Test
    fun `opened by the screen turning on, it shows over the lock screen only, and never opens the app`() {
        assertEquals(LockEntry.LYRICS, lockEntry(LockOrigin.SCREEN_ON, locked = true, loaded = true))
        assertEquals(LockEntry.NOTHING, lockEntry(LockOrigin.SCREEN_ON, locked = false, loaded = true))
        assertEquals(LockEntry.NOTHING, lockEntry(LockOrigin.SCREEN_ON, locked = true, loaded = false))
    }

    @Test
    fun `Show when locked opens the lyrics on screen-on only while KJB plays, the switch on and Display over other apps allowed`() {
        assertTrue(opensOnScreenOn(setting = true, overlayAllowed = true, playing = true, locked = true))
        assertFalse(opensOnScreenOn(setting = false, overlayAllowed = true, playing = true, locked = true))
        assertFalse(opensOnScreenOn(setting = true, overlayAllowed = false, playing = true, locked = true))
        assertFalse(opensOnScreenOn(setting = true, overlayAllowed = true, playing = false, locked = true))
        assertFalse(opensOnScreenOn(setting = true, overlayAllowed = true, playing = true, locked = false))
    }

    @Test
    fun `the switch asks for Display over other apps only when it is turned on without it`() {
        assertEquals(LockSwitch.ASK, lockSwitch(on = true, overlayAllowed = false))
        assertEquals(LockSwitch.STORE_ON, lockSwitch(on = true, overlayAllowed = true))
        assertEquals(LockSwitch.STORE_OFF, lockSwitch(on = false, overlayAllowed = false))
        assertEquals(LockSwitch.STORE_OFF, lockSwitch(on = false, overlayAllowed = true))
    }

    // Scrolling and closing (user feedback 2026-11-13, lane LK2)

    @Test
    fun `the lyrics scroll by finger as on the player, and only the bands around them close the screen`() {
        val lock = File("src/main/java/app/lyrify/ui/LockLyricsActivity.kt").readText()
        // The synced lines scroll as the player's do (SyncedLyrics' own follow), and the plain ones scroll whole.
        assertFalse("the synced lines are held still", lock.contains("scrolls = false"))
        assertFalse("the plain lines are cut short", lock.contains(".take(PLAIN_LINES)"))
        // The page: one swipe that closes, on its header band and its controls band, never over the lyrics.
        assertTrue("no page with bands", lock.contains("internal fun LockPage("))
        val page = lock.substringAfter("internal fun LockPage(").substringBefore("\n}\n")
        assertEquals(2, Regex("""\.swipeToClose\(onClose\)""").findAll(page).count())
        assertEquals(1, Regex("""fun Modifier\.swipeToClose\(""").findAll(lock).count())
        assertTrue(lock.substringAfter("private fun LockLyricsScreen(").substringBefore("\n}\n").contains("LockPage("))
        // Clear of the system's gesture edges at the top and the bottom, whatever the navigation mode.
        assertTrue(page.contains("WindowInsets.systemGestures.only(WindowInsetsSides.Vertical)"))
        // The controls band also clear of the side edges (Back on gesture navigation, nothing on 3 buttons), so a back
        // swipe never drags the bar.
        assertTrue("the bar sits in the back gesture's edge", page.contains("WindowInsets.systemGestures.only(WindowInsetsSides.Horizontal)"))
    }

    @Test
    fun `a swipe of the close distance on a band closes, counted from where the finger touched`() {
        assertTrue(swipeCloses(dy = 80f, closePx = 80f))
        assertTrue(swipeCloses(dy = -80f, closePx = 80f))
        assertTrue(swipeCloses(dy = 120f, closePx = 80f))
        assertFalse(swipeCloses(dy = 79.9f, closePx = 80f))
        assertFalse(swipeCloses(dy = -12f, closePx = 80f))
    }

    @Test
    fun `on a short window the header gives up its room first, the lyrics keep a line clear of the fades, and the page scrolls`() {
        // Lines 40 px tall fading over 28 px at the edges: the lyrics never get less than 96 px.
        assertEquals(96, lockLyricsMinPx(linePx = 40, fadePx = 28))
        // A tall window: the header keeps its 64 px of room, the lyrics take what the 200 px controls band leaves.
        assertEquals(64, lockHeaderMin(heightPx = 900, roomPx = 64, bottomPx = 200, leastLyricsPx = 96))
        assertEquals(636, lockLyricsPx(heightPx = 900, topPx = 64, bottomPx = 200, leastLyricsPx = 96))
        assertEquals(64, lockHeaderMin(heightPx = 360, roomPx = 64, bottomPx = 200, leastLyricsPx = 96))
        // Shorter: the header's room goes first, down to its own 48 px, and the lyrics keep more than their least.
        assertEquals(0, lockHeaderMin(heightPx = 350, roomPx = 64, bottomPx = 200, leastLyricsPx = 96))
        assertEquals(102, lockLyricsPx(heightPx = 350, topPx = 48, bottomPx = 200, leastLyricsPx = 96))
        // Shorter still (split screen, a large font): the lyrics keep their least, and the page scrolls (344 px in 300).
        assertEquals(96, lockLyricsPx(heightPx = 300, topPx = 48, bottomPx = 200, leastLyricsPx = 96))
    }

    @Test
    fun `the lyrics frame fits the room there is, the line playing always clear of the fades`() {
        val line = 40
        val fade = 28
        // Room enough: the lock screen's own frame.
        assertEquals(LockFrame, lockFrame(viewportPx = 600, linePx = line, fadePx = fade))
        // Short: the line playing moves to the middle, where the first line can rest too.
        val short = lockFrame(viewportPx = 100, linePx = line, fadePx = fade)
        assertEquals(0.5f, short.anchor)
        assertEquals(50f, short.top * 100 + line / 2f, 0.5f)
        // From the least height up, the line playing rests clear of both fades, and so do the first and the last.
        listOf(lockLyricsMinPx(line, fade), 100, 110, 119, 120, 150, 300, 600).forEach { height ->
            val frame = lockFrame(height, line, fade)
            val middle = frame.anchor * height
            assertTrue("$height: ${frame}", middle - line / 2f >= fade && middle + line / 2f <= height - fade)
            assertTrue("$height: ${frame}", frame.top * height >= fade - 0.5f)
            assertTrue("$height: ${frame}", frame.bottom * height >= fade - 0.5f)
        }
    }

    // The progress bar (user feedback 2026-11-13, point 4)

    @Test
    fun `a finger on the bar seeks to its share of the song, within the song`() {
        assertEquals(0.25f, barShare(x = 250f, width = 1_000f))
        assertEquals(0f, barShare(x = -40f, width = 1_000f))
        assertEquals(1f, barShare(x = 1_200f, width = 1_000f))
        assertEquals(0f, barShare(x = 500f, width = 0f))

        assertEquals(60_000L, seekMsAt(0.25f, durationMs = 240_000))
        assertEquals(0L, seekMsAt(0f, 240_000))
        assertEquals(240_000L, seekMsAt(1f, 240_000))
        // TalkBack can ask past either end: the start or the end of the song.
        assertEquals(0L, seekMsAt(-0.5f, 240_000))
        assertEquals(240_000L, seekMsAt(1.5f, 240_000))
        // A recording of three hours lands on its second, a pixel of a 1080 px bar from the middle too.
        assertEquals(5_400_000L, seekMsAt(barShare(540f, 1_080f), 10_800_000))
        assertEquals(5_410_000L, seekMsAt(barShare(541f, 1_080f), 10_800_000))
        // Without a length there is nowhere to seek.
        assertNull(seekMsAt(0.5f, 0))
    }

    @Test
    fun `only a deliberate sideways drag on the bar seeks, never a touch in a pocket or the swipe that closes`() {
        val slop = 24f
        // A touch that stays within the slop, however it wobbles, is no drag.
        assertFalse(lockSeekStarts(dx = 0f, dy = 0f, slop))
        assertFalse(lockSeekStarts(dx = 24f, dy = 0f, slop))
        assertFalse(lockSeekStarts(dx = -20f, dy = 10f, slop))
        // Past the slop and sideways, either way: a seek.
        assertTrue(lockSeekStarts(dx = 25f, dy = 0f, slop))
        assertTrue(lockSeekStarts(dx = -60f, dy = 12f, slop))
        assertTrue(lockSeekStarts(dx = 60f, dy = -30f, slop))
        // Slanted more than twice as far sideways as up or down, or straight up and down: never (the swipe takes it).
        assertFalse(lockSeekStarts(dx = 60f, dy = 31f, slop))
        assertFalse(lockSeekStarts(dx = 30f, dy = 80f, slop))
        assertFalse(lockSeekStarts(dx = 0f, dy = -200f, slop))
    }

    @Test
    fun `the bar moves on about a pixel at a time while the song plays`() {
        // Four minutes over 1080 px: a pixel every 222 ms.
        assertEquals(222L, lockBarTickMs(durationMs = 240_000, widthPx = 1_080))
        // A short clip: no more often than every frame.
        assertEquals(16L, lockBarTickMs(10_000, 1_080))
        // A long recording, or no length or bar yet: still four times a second, for the time shown.
        assertEquals(250L, lockBarTickMs(3_600_000, 1_080))
        assertEquals(250L, lockBarTickMs(0, 1_080))
        assertEquals(250L, lockBarTickMs(240_000, 0))
    }

    @Test
    fun `in a book the buttons beside Play and Pause jump back and forward 30 s, as the notification's, music keeps Previous and Next`() {
        val items = listOf(QueueItem(uid = 1, song = SongRef("/storage/emulated/0/Audiobooks/Quiet Orchard.m4b")))
        val book = Queue("b", QueueSource(SourceKind.BOOK, "/storage/emulated/0/Audiobooks/Quiet Orchard.m4b", "Quiet Orchard"), items)
        val music = Queue("m", QueueSource(SourceKind.OPENED, "opened", ""), items)
        assertEquals(LockSkip(R.drawable.ic_replay_30, -30_000) to LockSkip(R.drawable.ic_forward_30, 30_000), lockSkips(book))
        assertEquals(LockSkip(R.drawable.ic_skip_previous_fill) to LockSkip(R.drawable.ic_skip_next_fill), lockSkips(music))
        assertEquals(lockSkips(music), lockSkips(null))
        // The book's jumps are book mode's own, whatever the music's Skip amounts.
        val skips = skipsFor(book, PlaybackSettings(skipBackSeconds = 5, skipForwardSeconds = 10))
        assertEquals(-skips.backMs to skips.forwardMs, lockSkips(book).let { it.first.jumpMs to it.second.jumpMs })
        // The screen draws them for the queue playing.
        assertTrue(File("src/main/java/app/lyrify/ui/LockLyricsActivity.kt").readText().contains("lockSkips(ui.queue)"))
    }
}
