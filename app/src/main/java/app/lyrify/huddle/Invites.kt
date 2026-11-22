package app.lyrify.huddle

import app.lyrify.backup.SectionJson.jsonObject
import app.lyrify.backup.SectionJson.text
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.SecureRandom

/**
 * An invite as its link carries it (hd-research-check.md S4 and U2): the inviter's one-off P-256 [publicKey] and random
 * [token], the [name] the inviter goes by, and the first [card] when the invite was made from a song. Nothing of it
 * reaches Huddle's server: it all travels after the link's `#`, which a browser never sends.
 */
class Invite(val publicKey: ByteArray, val token: ByteArray, val name: String, val card: Card?)

/** The keys of one friendship, from this phone's side: a mailbox and a key each way, the same on both phones mirrored. */
class PairKeys(val outBox: String, val inBox: String, val outKey: ByteArray, val inKey: ByteArray)

/** What joining sends to the invite's join mailbox ([Invites.joinId]): this phone's [publicKey] and its sealed [hello]; and the friendship's [keys]. */
class Joining(val publicKey: ByteArray, val hello: ByteArray, val keys: PairKeys)

/** A friend who joined an invite: the [name] they gave, and the friendship's [keys]. */
class Joined(val name: String, val keys: PairKeys)

/**
 * Invites and the friendships they make, with no account and no server call to invite (hd-research-check.md S4):
 *
 * - The inviter's phone makes a key pair and a 128-bit token for this invite only, and a link
 *   `https://<host>/i#<code>`: the public key, the token and, sealed with a key from the token, the inviter's name and
 *   the first song.
 * - The friend's phone makes its own key pair, agrees a secret with the inviter's public key (ECDH), and from it, the
 *   token and both public keys derives the friendship's two mailboxes and two keys (HKDF). It posts its public key and a
 *   hello (its name, sealed) to the invite's join mailbox, whose id only the token gives.
 * - The inviter's phone reads the join, derives the same keys and opens the hello. Whoever has the link first becomes
 *   the friend, so the inviter sees who joined.
 *
 * The server only ever sees random mailbox ids and sealed boxes.
 */
object Invites {
    /** The path of an invite link on Huddle's hosts; the code follows the `#`. */
    const val PATH: String = "/i"
    const val TOKEN_BYTES: Int = 16

    /** The longest name, in characters. */
    const val NAME_MAX: Int = 40

    /** [invite]'s link on [host], Huddle's address that KJB opens links of (AndroidManifest.xml, App Links). */
    fun link(host: String, invite: Invite, random: SecureRandom): String {
        val details = JsonObject(
            buildMap {
                put(NAME_KEY, JsonPrimitive(invite.name))
                invite.card?.let { put(CARD_KEY, Messages.cardJson(it)) }
            },
        )
        val sealed = HuddleCrypto.seal(inviteKey(invite.token), details.toString().encodeToByteArray(), invite.publicKey, random)
        return "https://$host$PATH#" + HuddleCrypto.encode(byteArrayOf(FORMAT) + invite.publicKey + invite.token + sealed)
    }

    /**
     * The invite in [text]: a link on one of [hosts] (alone, or in a chat's message), or the code after its `#` pasted
     * alone; null for anything else, a link changed on the way, a code cut short or a key that is not one.
     */
    fun parse(text: String, hosts: Collection<String>): Invite? {
        val code = LINK.findAll(text).firstOrNull { link -> hosts.any { it.equals(link.groupValues[1], ignoreCase = true) } }?.groupValues?.get(2)
            ?: text.trim().takeIf(CODE::matches)
            ?: return null
        val bytes = HuddleCrypto.decode(code)?.takeIf { it.size > HEADER_BYTES && it[0] == FORMAT } ?: return null
        val key = bytes.copyOfRange(1, 1 + HuddleCrypto.PUBLIC_KEY_BYTES).takeIf(HuddleCrypto::isPublicKey) ?: return null
        val token = bytes.copyOfRange(1 + HuddleCrypto.PUBLIC_KEY_BYTES, HEADER_BYTES)
        val details = HuddleCrypto.open(inviteKey(token), bytes.copyOfRange(HEADER_BYTES, bytes.size), key)?.let { jsonObject(it.decodeToString()) } ?: return null
        val name = details.text(NAME_KEY)?.let(::cleanName) ?: return null
        val card = (details[CARD_KEY] as? JsonObject)?.let { Messages.cardOf(it) ?: return null }
        return Invite(key, token, name, card)
    }

    /** The join mailbox of the invite with [token]: only the two phones know it, and it says nothing of the token. */
    fun joinId(token: ByteArray): String = HuddleCrypto.encode(HuddleCrypto.hkdf(token, ByteArray(0), JOIN_INFO, HuddleCrypto.KEY_BYTES))

    /** Joining [invite] as [name]: a key pair of this phone's own, the friendship's keys, and the sealed hello; null when the invite's key is not one. */
    fun join(invite: Invite, name: String, random: SecureRandom): Joining? {
        val own = HuddleCrypto.keyPair(random)
        val secret = HuddleCrypto.agree(own.private, invite.publicKey) ?: return null
        val keys = pairKeys(secret, invite.token, inviterKey = invite.publicKey, joinerKey = own.public, inviter = false)
        val hello = JsonObject(mapOf(NAME_KEY to JsonPrimitive(name))).toString().encodeToByteArray()
        return Joining(own.public, HuddleCrypto.seal(keys.outKey, hello, joinId(invite.token).encodeToByteArray(), random), keys)
    }

    /**
     * The friend who joined the invite made with [own] key pair and [token], from what they posted: their [joinerKey]
     * and [hello]. Null when the hello was not sealed for this invite by that key.
     */
    fun joined(own: KeyPairBytes, token: ByteArray, joinerKey: ByteArray, hello: ByteArray): Joined? {
        val secret = HuddleCrypto.agree(own.private, joinerKey) ?: return null
        val keys = pairKeys(secret, token, inviterKey = own.public, joinerKey = joinerKey, inviter = true)
        val opened = HuddleCrypto.open(keys.inKey, hello, joinId(token).encodeToByteArray()) ?: return null
        val name = jsonObject(opened.decodeToString())?.text(NAME_KEY)?.let(::cleanName) ?: return null
        return Joined(name, keys)
    }

    /**
     * A name as KJB keeps it: control characters as spaces, spaces as one, no direction marks (they could make a name
     * read as another), at most [NAME_MAX] characters; null when nothing is left.
     */
    fun cleanName(text: String): String? =
        clip(text.map { if (it.isISOControl()) ' ' else it }.filterNot { it in DIRECTION_MARKS }.joinToString("").replace(SPACES, " "), NAME_MAX)

    /** The friendship's mailboxes and keys from the agreed [secret], the [token] and both public keys; [inviter]'s side or the joiner's. */
    private fun pairKeys(secret: ByteArray, token: ByteArray, inviterKey: ByteArray, joinerKey: ByteArray, inviter: Boolean): PairKeys {
        val size = HuddleCrypto.KEY_BYTES
        val all = HuddleCrypto.hkdf(secret, token, PAIR_INFO + inviterKey + joinerKey, 4 * size)
        val toJoiner = HuddleCrypto.encode(all.copyOfRange(0, size))
        val toInviter = HuddleCrypto.encode(all.copyOfRange(size, 2 * size))
        val keyToJoiner = all.copyOfRange(2 * size, 3 * size)
        val keyToInviter = all.copyOfRange(3 * size, 4 * size)
        return if (inviter) PairKeys(toJoiner, toInviter, keyToJoiner, keyToInviter) else PairKeys(toInviter, toJoiner, keyToInviter, keyToJoiner)
    }

    /** The key that seals an invite's name and song: from its token, so the link alone opens it, and a changed link does not. */
    private fun inviteKey(token: ByteArray): ByteArray = HuddleCrypto.hkdf(token, ByteArray(0), INVITE_INFO, HuddleCrypto.KEY_BYTES)

    /** The invite link's format, the code's first byte. */
    private const val FORMAT: Byte = 1
    private const val HEADER_BYTES: Int = 1 + HuddleCrypto.PUBLIC_KEY_BYTES + TOKEN_BYTES
    private const val NAME_KEY = "n"
    private const val CARD_KEY = "c"
    private val JOIN_INFO = "KJB Huddle 1 join".encodeToByteArray()
    private val INVITE_INFO = "KJB Huddle 1 invite".encodeToByteArray()
    private val PAIR_INFO = "KJB Huddle 1 pair".encodeToByteArray()

    /** An invite link in any text: https, a host, the path, then the code (base64url). */
    private val LINK = Regex("""https://([A-Za-z0-9.-]+)(?::443)?/i/?#([A-Za-z0-9_-]+)""")

    /** An invite's code pasted alone; never longer than a link could carry. */
    private val CODE = Regex("""[A-Za-z0-9_-]{100,4000}""")
    private val SPACES = Regex("""\s+""")
    private const val DIRECTION_MARKS = "؜‎‏‪‫‬‭‮⁦⁧⁨⁩"
}
