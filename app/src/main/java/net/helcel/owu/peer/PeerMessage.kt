package net.helcel.owu.peer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.helcel.owu.helper.IdentityCard
import net.helcel.owu.ledger.ExchangeAgreement
import net.helcel.owu.ledger.Iou

/**
 * What two devices say over a link. No reconciliation: each message is one
 * step of an action a user took, carrying the whole chain it is about so the
 * receiver verifies it from genesis.
 */
@Serializable
sealed class PeerMessage {

    // --- handshake -----------------------------------------------------------

    /** First message from each side: who I am and a challenge for you. */
    @Serializable
    @SerialName("HELLO")
    data class Hello(val identity: IdentityCard, val nonce: String) : PeerMessage()

    /** Proof that I hold the key in my HELLO: a signature over your nonce and my key. */
    @Serializable
    @SerialName("AUTH")
    data class Auth(val signature: String) : PeerMessage()

    // --- trade ---------------------------------------------------------------
    // Two sides, a bundle or nothing on each. Both accept the same table and
    // it happens: EXCHANGE if both brought something, TRANSFER if one did.
    // Issuing, giving and swapping are all this.

    /** The two things one side can ask of the other, and so the two it can refuse. */
    @Serializable
    enum class Asked { INVITE, TABLE }

    /**
     * No, not just now. Clears the refused table on both sides, so nothing
     * sits refused; it exists so the asker is told instead of waiting for ever.
     */
    @Serializable
    @SerialName("DECLINE")
    data class Decline(val key: String, val to: Asked) : PeerMessage()

    /**
     * I have opened a table and would like you at it. No ledger state, no
     * answer: without it, wanting to trade reaches nobody not already looking.
     */
    @Serializable
    @SerialName("INVITE")
    data class Invite(val key: String) : PeerMessage()

    /**
     * What I put on the table: OwUs I hold, or nothing. [key] is who I am,
     * so a scanned code knows its counterparty even with nothing on it.
     */
    @Serializable
    @SerialName("TABLE")
    data class Table(val key: String, val ious: List<Iou> = emptyList()) : PeerMessage()

    /**
     * I accept the table as I see it: my [ious] against yours, [deal]
     * identifying it. For a swap, [agreement] is half-signed going first,
     * countersigned answering; complete, both sides apply it.
     */
    @Serializable
    @SerialName("ACCEPT")
    data class Accept(
        val key: String,
        val deal: String,
        val ious: List<Iou> = emptyList(),
        val agreement: ExchangeAgreement? = null,
    ) : PeerMessage()

    /** My side of an accepted table, signed over to you: a gift, and the last message of it. */
    @Serializable
    @SerialName("GIVE")
    data class Give(val ious: List<Iou>) : PeerMessage()

    // --- redeem --------------------------------------------------------------
    // Nothing waits: an OwU handed back to whoever owes it comes home closed.

    /** Debtor to holder: their OwU back, with my signature voiding it. The receipt. */
    @Serializable
    @SerialName("REDEEMED")
    data class Redeemed(val iou: Iou) : PeerMessage()

    companion object {
        val json = Json {
            classDiscriminator = "type"
            encodeDefaults = true
            explicitNulls = false
            ignoreUnknownKeys = true
        }

        fun encode(m: PeerMessage): ByteArray = json.encodeToString(serializer(), m).toByteArray(Charsets.UTF_8)
        fun decode(bytes: ByteArray): PeerMessage = json.decodeFromString(serializer(), bytes.toString(Charsets.UTF_8))
    }
}
