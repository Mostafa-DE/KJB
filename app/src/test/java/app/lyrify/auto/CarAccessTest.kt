package app.lyrify.auto

import androidx.media3.common.Player
import app.lyrify.library.Library
import app.lyrify.library.song
import app.lyrify.player.Found
import app.lyrify.player.QueueSource
import app.lyrify.player.SourceKind
import app.lyrify.settings.PlaybackSettings
import app.lyrify.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who may see KJB's library through its media session, the car's cover grants, the covers the provider serves, and
 * the car's searches. Controllers are made up ([Caller]); signing certificates are checked by a fake. Made-up names only.
 */
class CarAccessTest {
    private val ownUid = 10_123
    private val autoUid = 10_200
    private val centreUid = 1_000
    private val platformCertificate = "AB".repeat(32)

    /** Which certificate each uid's app is signed with. */
    private val signers = mapOf(
        autoUid to ControllerAccess.AUTO_CERTIFICATES.first(),
        centreUid to platformCertificate,
        10_300 to "CD".repeat(32),
    )
    private val access = ControllerAccess(
        ownPackage = "app.lyrify.debug",
        ownUid = ownUid,
        signedBy = { uid, sha256 -> signers[uid] == sha256 },
        platform = { setOf(platformCertificate) },
    )

    // Who sees the library

    @Test
    fun `KJB itself and the system's trusted controllers get the library`() {
        assertEquals(Access.LIBRARY, access.of(Caller("app.lyrify.debug", ownUid, trusted = false)))
        assertEquals(Access.LIBRARY, access.of(Caller("com.android.systemui", 10_050, trusted = true)))
        assertEquals(Access.LIBRARY, access.of(Caller("com.android.bluetooth", 1_002, trusted = true)))
    }

    @Test
    fun `Android Auto and an Automotive media centre are cars only when signed so`() {
        assertEquals(Access.CAR, access.of(Caller(ControllerAccess.ANDROID_AUTO, autoUid, trusted = false)))
        assertEquals(Access.CAR, access.of(Caller(ControllerAccess.ANDROID_AUTO, autoUid, trusted = true)))
        assertEquals(Access.CAR, access.of(Caller("com.android.car.media", centreUid, trusted = false)))
        // The names alone: an app calling itself Android Auto or the media centre, signed with its own key.
        assertEquals(Access.TRANSPORT, access.of(Caller(ControllerAccess.ANDROID_AUTO, 10_300, trusted = false)))
        assertEquals(Access.TRANSPORT, access.of(Caller("com.android.car.media", 10_300, trusted = false)))
        // Signed with the platform key, but not a media centre: not a car.
        assertEquals(Access.TRANSPORT, access.of(Caller("com.example.other", centreUid, trusted = false)))
    }

    @Test
    fun `any other app gets plain transport, KJB's name from another uid included`() {
        assertEquals(Access.TRANSPORT, access.of(Caller("com.example.player", 10_400, trusted = false)))
        assertEquals(Access.TRANSPORT, access.of(Caller("app.lyrify.debug", 10_401, trusted = false)))
    }

    @Test
    fun `plain transport is play, pause, next, previous and the song playing, never the queue or a change to it`() {
        val transport = ControllerAccess.TRANSPORT_COMMANDS
        listOf(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_METADATA)
            .forEach { assertTrue("missing $it", it in transport) }
        listOf(Player.COMMAND_GET_TIMELINE, Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS, Player.COMMAND_SET_SHUFFLE_MODE, Player.COMMAND_SET_REPEAT_MODE)
            .forEach { assertFalse("has $it", it in transport) }
    }

    // The car's cover grants

    private val log = mutableListOf<String>()
    private val grants = CoverGrants<String>(
        packageOf = { it.substringBefore('#') },
        grant = { packageName, id -> log += "grant $packageName $id" },
        revoke = { packageName, id -> log += "revoke $packageName $id" },
    )

    @Test
    fun `a car gets each cover it is shown once, and an app not connected as a car gets none`() {
        grants.connected("auto#1")
        grants.give("auto#1", listOf(4L, 5L))
        grants.give("auto#1", listOf(5L, 6L))
        grants.give("other#1", listOf(7L))
        assertEquals(listOf("grant auto 4", "grant auto 5", "grant auto 6"), log)
    }

    @Test
    fun `a car's grants go when it disconnects, except those another connection of that app still shows`() {
        grants.connected("auto#1")
        grants.connected("auto#2")
        grants.give("auto#1", listOf(4L, 5L))
        grants.give("auto#2", listOf(5L))
        log.clear()
        grants.disconnected("auto#1")
        assertEquals(listOf("revoke auto 4"), log)
        grants.disconnected("auto#2")
        assertEquals(listOf("revoke auto 4", "revoke auto 5"), log)
        grants.give("auto#2", listOf(9L))
        grants.disconnected("auto#2")
        assertEquals(2, log.size)
    }

    // The covers the provider serves

    @Test
    fun `covers are served only for songs in the library`() {
        val shown = song("Music/Rips/Loose Thread.mp3", id = 20)
        val clip = song("Music/Rips/Short Bell.mp3", id = 21, durationMs = 5_000)
        val noId = song("Music/Rips/Ember Road.mp3")
        val library = Library.of(listOf(shown, clip, noId), Settings(playback = PlaybackSettings(skipShortClips = true)))
        assertEquals(setOf(20L), coverIds(library))
        assertTrue(CarCovers.allowed(20, library))
        assertFalse(CarCovers.allowed(21, library))
        assertFalse(CarCovers.allowed(99, library))
        assertFalse(CarCovers.allowed(20, null))
    }

    // The car's searches

    @Test
    fun `two apps' searches never take each other's results, and only the latest are kept`() {
        val searches = Searches()
        val source = QueueSource(SourceKind.SEARCH, "search:road", "road")
        val loose = song("Music/Rips/Loose Thread.mp3", id = 20)
        val ember = song("Music/Rips/Ember Road.mp3", id = 21)
        searches.put("auto", Found("road", listOf(ember), source))
        searches.put("centre", Found("road", listOf(loose), source))
        assertEquals(listOf(ember), searches.of("auto")["road"]!!.songs)
        assertEquals(listOf(loose), searches.of("centre")["road"]!!.songs)
        repeat(Searches.KEPT) { searches.put("auto", Found("words $it", emptyList(), source)) }
        assertNull(searches.of("auto")["road"])
        assertEquals(Searches.KEPT, searches.of("auto").size + searches.of("centre").size)
    }
}
