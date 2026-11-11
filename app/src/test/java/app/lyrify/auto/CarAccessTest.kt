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
    fun `the player's own buttons (Favourite, Shuffle, Repeat) are offered to KJB, the system and the car, never another app`() {
        val base = setOf("browse", "search")
        val own = listOf("favourite", "shuffle", "repeat")
        fun offered(caller: Caller) = ControllerAccess.sessionCommands(access.of(caller), base, own)

        assertEquals(base + own, offered(Caller("app.lyrify.debug", ownUid, trusted = false)))
        assertEquals(base + own, offered(Caller("com.android.systemui", 10_050, trusted = true)))
        assertEquals(base + own, offered(Caller(ControllerAccess.ANDROID_AUTO, autoUid, trusted = false)))
        assertEquals(emptySet<String>(), offered(Caller("com.example.player", 10_400, trusted = false)))
        assertEquals(emptySet<String>(), offered(Caller(ControllerAccess.ANDROID_AUTO, 10_300, trusted = false)))
    }

    // What each controller is offered as it connects. On the phone [every] and [sessionDefaults] are Media3's
    // ConnectionResult defaults; Media3 1.11's own onConnect offers no command at all, so the offer never starts from it.

    /** Every player command, by code. */
    private val every = (Player.COMMAND_PLAY_PAUSE..Player.COMMAND_SET_AUDIO_ATTRIBUTES).toSet()
    private val sessionDefaults = setOf("session", "library root", "children", "search")
    private val parts = listOf("favourite", "shuffle", "repeat")

    private fun offered(caller: Caller, rule: ControllerAccess = access) = ControllerAccess.offer(rule.of(caller), every, sessionDefaults, parts)

    @Test
    fun `KJB's own in-process controller gets every command and the library, the debug build's package and the release's alike`() {
        // KJB's own controller is not trusted in Media3's eyes when KJB has no notification access.
        val debug = offered(Caller("app.lyrify.debug", ownUid, trusted = false))
        assertTrue("play and pause refused", Player.COMMAND_PLAY_PAUSE in debug.player)
        assertEquals(every, debug.player)
        assertEquals(sessionDefaults + parts, debug.session)

        val release = ControllerAccess(ownPackage = "app.lyrify", ownUid = ownUid, signedBy = { _, _ -> false }, platform = { emptySet() })
        assertEquals(Offer(every, sessionDefaults + parts), offered(Caller("app.lyrify", ownUid, trusted = false), release))
        // The release's rule never takes the debug build for itself, nor the other way round.
        assertEquals(emptySet<String>(), offered(Caller("app.lyrify.debug", ownUid, trusted = false), release).session)
        assertEquals(emptySet<String>(), offered(Caller("app.lyrify", ownUid, trusted = false)).session)
    }

    @Test
    fun `the notification, the lock screen, Samsung's trusted controllers and a signed car get every command and the library`() {
        val full = Offer(every, sessionDefaults + parts)
        assertEquals(full, offered(Caller("com.android.systemui", 10_050, trusted = true)))
        assertEquals(full, offered(Caller("com.samsung.android.app.routineplus", 10_060, trusted = true)))
        assertEquals(full, offered(Caller(ControllerAccess.ANDROID_AUTO, autoUid, trusted = false)))
        assertEquals(full, offered(Caller("com.android.car.media", centreUid, trusted = false)))
    }

    @Test
    fun `any other app gets plain transport (play, pause, skip, seek) and no library or custom command`() {
        for (stranger in listOf(Caller("com.example.player", 10_400, trusted = false), Caller(ControllerAccess.ANDROID_AUTO, 10_300, trusted = false), Caller("app.lyrify.debug", 10_401, trusted = false))) {
            val offer = offered(stranger)
            listOf(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                .forEach { assertTrue("$stranger misses $it", it in offer.player) }
            assertEquals(ControllerAccess.TRANSPORT_COMMANDS.toSet(), offer.player)
            assertEquals(emptySet<String>(), offer.session)
        }
    }

    @Test
    fun `plain transport is play, pause, next, previous, seek and the song playing, never the queue or a change to it`() {
        val transport = ControllerAccess.TRANSPORT_COMMANDS
        listOf(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_METADATA)
            .forEach { assertTrue("missing $it", it in transport) }
        listOf(Player.COMMAND_GET_TIMELINE, Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS, Player.COMMAND_SET_SHUFFLE_MODE, Player.COMMAND_SET_REPEAT_MODE)
            .forEach { assertFalse("has $it", it in transport) }
    }

    // The car's cover grants

    private val log = mutableListOf<String>()
    private val grants = CoverGrants<String>(
        packageOf = { it.substringBefore('#') },
        grant = { packageName -> log += "grant $packageName" },
        revoke = { packageName -> log += "revoke $packageName" },
    )

    @Test
    fun `a car app may read every cover from its first connection, once, and an app not connected as a car never`() {
        grants.connected("auto#1")
        grants.connected("auto#1")
        grants.connected("auto#2")
        grants.connected("centre#1")
        assertEquals(listOf("grant auto", "grant centre"), log)
        grants.disconnected("other#1")
        assertEquals(2, log.size)
    }

    @Test
    fun `a car app's grant goes with its last connection, and comes again with a new one`() {
        grants.connected("auto#1")
        grants.connected("auto#2")
        log.clear()
        grants.disconnected("auto#1")
        assertEquals(emptyList<String>(), log)
        grants.disconnected("auto#2")
        assertEquals(listOf("revoke auto"), log)
        grants.disconnected("auto#2")
        grants.connected("auto#3")
        assertEquals(listOf("revoke auto", "grant auto"), log)
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

    @Test
    fun `the provider serves a song's own cover, else the default cover, and never one of a song outside the library`() {
        val withArt = song("Music/Rips/Loose Thread.mp3", id = 20)
        val noArt = song("Music/Rips/Ember Road.mp3", id = 22)
        val library = Library.of(listOf(withArt, noArt), Settings())
        val read = mutableListOf<Long>()
        fun serve(path: String) = coverFor(path, { CarCovers.allowed(it, library) }, { id -> read += id; if (id == 20L) "cover 20" else null }) { "default" }

        assertEquals("cover 20", serve("20"))
        // No embedded cover: the default, so the car never shows a gap.
        assertEquals("default", serve("22"))
        // Not a song in the library, or not an id at all: the default, and no file is read.
        assertEquals("default", serve("99"))
        assertEquals("default", serve(DEFAULT_COVER))
        assertEquals("default", serve("../Music"))
        assertEquals(listOf(20L, 22L), read)
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
