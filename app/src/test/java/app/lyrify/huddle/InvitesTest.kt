package app.lyrify.huddle

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/** Invite links on both of Huddle's hosts, pasted or opened, and the friendship both phones make from one. Made-up names only. */
class InvitesTest {
    private val random = SecureRandom()
    private val railway = "kjb-huddle.up.railway.app"
    private val domain = "huddle.example.org"
    private val hosts = listOf(railway, domain)
    private val song = CardSong("Paper Lantern", "Kit Calloway", "Harbour Glass", 201_000)

    private fun invite(name: String = "Wren", card: Card? = Card(CardKind.SONG, listOf(song), note = "for the drive")): Pair<KeyPairBytes, Invite> {
        val pair = HuddleCrypto.keyPair(random)
        return pair to Invite(pair.public, ByteArray(Invites.TOKEN_BYTES).also(random::nextBytes), name, card)
    }

    @Test
    fun `a link made on either host opens on both, with the name and the first song`() {
        val (_, made) = invite()
        hosts.forEach { host ->
            val link = Invites.link(host, made, random)
            assertTrue(link, link.startsWith("https://$host/i#"))
            val read = Invites.parse(link, hosts)
            assertNotNull(link, read)
            assertArrayEquals(made.publicKey, read!!.publicKey)
            assertArrayEquals(made.token, read.token)
            assertEquals("Wren", read.name)
            assertEquals(made.card, read.card)
        }
        // A host in capitals is the same host.
        assertNotNull(Invites.parse(Invites.link(railway, made, random).replace(railway, railway.uppercase()), hosts))
    }

    @Test
    fun `an invite without a song carries the name alone`() {
        val (_, made) = invite(card = null)
        val read = Invites.parse(Invites.link(domain, made, random), hosts)!!
        assertEquals("Wren", read.name)
        assertNull(read.card)
    }

    @Test
    fun `a link inside a chat message, or the code after the hash alone, is found`() {
        val (_, made) = invite()
        val link = Invites.link(railway, made, random)
        assertNotNull(Invites.parse("I'm sending you Paper Lantern on KJB: $link\nsee you", hosts))
        assertNotNull(Invites.parse("  ${link.substringAfter('#')}\n", hosts))
    }

    @Test
    fun `a link on another host, over http, changed or cut short is not an invite`() {
        val (_, made) = invite()
        val link = Invites.link(railway, made, random)
        assertNull(Invites.parse(link.replace(railway, "huddle.example.net"), hosts))
        assertNull(Invites.parse(link.replace("https://", "http://"), hosts))
        assertNull(Invites.parse(link.dropLast(4), hosts))
        val code = link.substringAfter('#')
        val changed = code.substring(0, 100) + (if (code[100] == 'A') 'B' else 'A') + code.substring(101)
        assertNull(Invites.parse(changed, hosts))
        assertNull(Invites.parse("", hosts))
        assertNull(Invites.parse("hello there", hosts))
    }

    @Test
    fun `the join mailbox is the same for both phones, secret, and one per invite`() {
        val (_, one) = invite()
        val (_, two) = invite()
        val id = Invites.joinId(one.token)
        assertEquals(43, id.length)
        assertEquals(id, Invites.joinId(one.token.copyOf()))
        assertNotEquals(id, Invites.joinId(two.token))
        assertFalse(id.contains(HuddleCrypto.encode(one.token)))
    }

    @Test
    fun `joining gives both phones one friendship, mirrored, with each other's names`() {
        val (own, made) = invite(name = "Wren")
        val read = Invites.parse(Invites.link(railway, made, random), hosts)!!
        val joining = Invites.join(read, "Juniper", random)!!
        val joined = Invites.joined(own, made.token, joining.publicKey, joining.hello)!!
        assertEquals("Juniper", joined.name)
        assertEquals(joining.keys.outBox, joined.keys.inBox)
        assertEquals(joining.keys.inBox, joined.keys.outBox)
        assertArrayEquals(joining.keys.outKey, joined.keys.inKey)
        assertArrayEquals(joining.keys.inKey, joined.keys.outKey)
        assertNotEquals(joined.keys.outBox, joined.keys.inBox)
        assertFalse(joined.keys.outKey.contentEquals(joined.keys.inKey))
        assertEquals(43, joined.keys.outBox.length)
        assertEquals(32, joined.keys.outKey.size)
    }

    @Test
    fun `a hello for another invite, or with another key, is refused`() {
        val (own, made) = invite()
        val (_, other) = invite()
        val joining = Invites.join(made, "Juniper", random)!!
        val elsewhere = Invites.join(other, "Juniper", random)!!
        assertNull(Invites.joined(own, made.token, elsewhere.publicKey, elsewhere.hello))
        assertNull(Invites.joined(own, made.token, joining.publicKey, elsewhere.hello))
        assertNull(Invites.joined(own, made.token, HuddleCrypto.keyPair(random).public, joining.hello))
        assertNull(Invites.joined(own, made.token, joining.publicKey.copyOf(64), joining.hello))
    }

    @Test
    fun `an invite whose key is not a point of P-256 cannot be joined`() {
        val (_, made) = invite()
        val broken = Invite(made.publicKey.copyOf().also { it[64] = (it[64] + 1).toByte() }, made.token, made.name, null)
        assertNull(Invites.join(broken, "Juniper", random))
    }

    @Test
    fun `a name is trimmed, kept short and never blank`() {
        assertEquals("Wren", Invites.cleanName("  Wren \n"))
        assertEquals("Wren Hale", Invites.cleanName("Wren   Hale"))
        assertEquals(Invites.NAME_MAX, Invites.cleanName("x".repeat(100))!!.length)
        assertEquals("وردة", Invites.cleanName("وردة"))
        assertNull(Invites.cleanName("   "))
        assertNull(Invites.cleanName(""))
        assertEquals("Wren Hale", Invites.cleanName("Wren\u0000Hale\u200E"))
    }
}
