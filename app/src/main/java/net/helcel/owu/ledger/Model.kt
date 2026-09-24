package net.helcel.owu.ledger

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.helcel.owu.crypto.Canonical
import net.helcel.owu.crypto.Hash

/**
 * One IOU: a signed chain of custody. The chain is only meaningful once
 * [Verifier.verify] has accepted it; nothing here checks anything.
 */
@Serializable
data class Iou(
    @SerialName("iou_id") val id: String,
    val metadata: Metadata,
    @SerialName("ledger_chain") val chain: List<Block>,
) {
    val head: Block get() = chain.last()
    fun headHash(): String = head.hash(id)
}

/**
 * What the IOU is. Covered by the genesis signature through [hash], so a
 * "1 Heavy Hug" cannot become "1 Car" after issue.
 *
 * [notBefore]/[notAfter] (epoch seconds) bound redemption. Like [geoloc] they
 * are shown, not enforced: clocks are self-reported, and the debtor decides.
 */
@Serializable
data class Metadata(
    @SerialName("display_title") val title: String,
    /** Whatever else the promise needs said: terms, an occasion, a joke. */
    @SerialName("description") val description: String? = null,
    @SerialName("template_id") val templateId: String? = null,
    val geoloc: GeoLoc? = null,
    @SerialName("not_before") val notBefore: Long? = null,
    @SerialName("not_after") val notAfter: Long? = null,
) {
    // All of it signed with the genesis block, description included: neither
    // side can edit what was promised.
    fun canonical(): Map<String, Any?> = mapOf(
        "display_title" to title,
        "description" to description,
        "template_id" to templateId,
        "geoloc" to geoloc?.canonical(),
        "not_before" to notBefore,
        "not_after" to notAfter,
    )

    fun hash(): String = Hash.sha256Hex(Canonical.bytes(canonical()))

    val hasWindow: Boolean get() = notBefore != null || notAfter != null

    /** Where [now] falls relative to the redemption window. */
    fun timeGate(now: Long = Ledger.now()): TimeGate = when {
        notBefore != null && now < notBefore -> TimeGate.NotYet(notBefore)
        notAfter != null && now > notAfter -> TimeGate.Expired(notAfter)
        else -> TimeGate.Open
    }
}

sealed class TimeGate {
    object Open : TimeGate()
    data class NotYet(val opensAt: Long) : TimeGate()
    data class Expired(val closedAt: Long) : TimeGate()
}

/** Where a promise is to be kept. Micro-degrees, so the signed form is
 *  integer-only. Never verified: a hint the app shows, not a rule. */
@Serializable
data class GeoLoc(
    @SerialName("lat_e6") val latE6: Int,
    @SerialName("lon_e6") val lonE6: Int,
    @SerialName("radius_m") val radiusM: Int,
    val label: String? = null,
) {
    val latitude: Double get() = latE6 / 1e6
    val longitude: Double get() = lonE6 / 1e6

    fun canonical(): Map<String, Any?> = mapOf(
        "lat_e6" to latE6,
        "lon_e6" to lonE6,
        "radius_m" to radiusM,
        "label" to label,
    )

    companion object {
        fun of(latitude: Double, longitude: Double, radiusM: Int, label: String? = null) = GeoLoc(
            latE6 = Math.round(latitude * 1e6).toInt(),
            lonE6 = Math.round(longitude * 1e6).toInt(),
            radiusM = radiusM,
            label = label,
        )
    }
}

enum class Action { ISSUE, TRANSFER, REDEEMED }

/**
 * One state change; the wire `action` is the subclass's serial name. Bound to
 * its IOU (the id is signed) and to its predecessor via [parentHash], the
 * SHA-256 of the previous block's canonical form, signature included. By hash
 * and not by the raw signature, so ECDSA malleability cannot make two
 * byte-different chains of one history.
 */
@Serializable
sealed class Block {
    abstract val sequence: Int
    abstract val timestamp: Long
    abstract val parentHash: String
    abstract val signature: String

    abstract val action: Action

    /** Fields specific to this action, without the common ones or the signature. */
    protected abstract fun fields(): Map<String, Any?>

    /** The identity whose key must have produced [signature], or null if the block cannot say. */
    abstract fun signer(iouId: String): String?

    /** Everything the signature covers, as a canonical map. */
    fun payload(iouId: String): Map<String, Any?> = fields() + mapOf(
        "iou_id" to iouId,
        "sequence" to sequence,
        "action" to action.name,
        "timestamp" to timestamp,
        "parent_hash" to parentHash,
    )

    /** The bytes [signature] is over. The payload, unless a swap signs its agreement instead. */
    open fun signedBytes(iouId: String): ByteArray = Canonical.bytes(payload(iouId))

    /** The value the next block's [parentHash] must carry. */
    fun hash(iouId: String): String =
        Hash.sha256Hex(Canonical.bytes(payload(iouId) + ("signature" to signature)))

    /** Block 0: the debtor writes the OwU and hands it to the creditor. */
    @Serializable
    @SerialName("ISSUE")
    data class Issue(
        override val sequence: Int = 0,
        override val timestamp: Long,
        @SerialName("parent_hash") override val parentHash: String = Hash.ZERO,
        @SerialName("debtor_pub_key") val debtor: String,
        @SerialName("creditor_pub_key") val creditor: String,
        @SerialName("metadata_hash") val metadataHash: String,
        override val signature: String = "",
    ) : Block() {
        override val action get() = Action.ISSUE
        override fun fields() = mapOf(
            "debtor_pub_key" to debtor,
            "creditor_pub_key" to creditor,
            "metadata_hash" to metadataHash,
        )

        override fun signer(iouId: String) = debtor
    }

    /**
     * The holder passes the OwU on: plain, a gift or a hand-back.
     *
     * With an [agreement], one half of a swap. The same agreement lands on
     * both chains, each block signed by that chain's holder with their
     * signature from it, so once both have signed either party can append
     * both, and neither half is valid alone. That is why such a block signs
     * the agreement and not its own payload: one signature has to commit to
     * both sides.
     */
    @Serializable
    @SerialName("TRANSFER")
    data class Transfer(
        override val sequence: Int,
        override val timestamp: Long,
        @SerialName("parent_hash") override val parentHash: String,
        @SerialName("transferor_pub_key") val transferor: String,
        @SerialName("transferee_pub_key") val transferee: String,
        val agreement: ExchangeAgreement? = null,
        override val signature: String = "",
    ) : Block() {
        override val action get() = Action.TRANSFER
        // A null agreement is dropped by Canonical, so a plain hand-over signs
        // exactly the two keys and the common fields.
        override fun fields() = mapOf(
            "transferor_pub_key" to transferor,
            "transferee_pub_key" to transferee,
            "agreement" to agreement?.canonical(),
        )

        override fun signer(iouId: String) = transferor
        override fun signedBytes(iouId: String) =
            agreement?.signingBytes() ?: super.signedBytes(iouId)
    }

    /** The debtor honours the promise: the OwU is redeemed. Terminal. */
    @Serializable
    @SerialName("REDEEMED")
    data class Redeemed(
        override val sequence: Int,
        override val timestamp: Long,
        @SerialName("parent_hash") override val parentHash: String,
        @SerialName("debtor_pub_key") val debtor: String,
        override val signature: String = "",
    ) : Block() {
        override val action get() = Action.REDEEMED
        override fun fields() = mapOf("debtor_pub_key" to debtor)
        override fun signer(iouId: String) = debtor
    }
}

/** One OwU in an exchange, pinned to the head it was agreed against. */
@Serializable
data class ExchangeRef(
    @SerialName("iou_id") val iouId: String,
    @SerialName("head_hash") val headHash: String,
) {
    fun canonical(): Map<String, Any?> = mapOf("iou_id" to iouId, "head_hash" to headHash)
}

/** One party to an exchange: all they put in, and their consent to the whole.
 *  Five beers and a hug are one deal, signed once, all or nothing. */
@Serializable
data class ExchangeSide(
    @SerialName("holder_pub_key") val holder: String,
    val ious: List<ExchangeRef> = emptyList(),
    /** The holder's signature over [ExchangeAgreement.signingBytes]; null until they have signed. */
    val signature: String? = null,
) {
    /** Ordered, so both sides sign the same bytes whatever order they built it in. */
    fun canonical(): Map<String, Any?> = mapOf(
        "holder_pub_key" to holder,
        "ious" to ious.sortedBy { it.iouId }.map { it.canonical() },
    )

    fun ref(iouId: String): ExchangeRef? = ious.firstOrNull { it.iouId == iouId }

    fun holds(iouId: String): Boolean = ref(iouId) != null
}

/** [left] for [right], both holders signing the same bytes: the core without
 *  either signature. Every OwU is pinned to a head hash, so one of them
 *  moving before the swap lands voids the whole bundle. */
@Serializable
data class ExchangeAgreement(
    @SerialName("exchange_id") val id: String,
    val timestamp: Long,
    val left: ExchangeSide,
    val right: ExchangeSide,
) {
    /** The core with both signatures, so a block hash covers the consent that made it valid. */
    fun canonical(): Map<String, Any?> = mapOf(
        "exchange_id" to id,
        "timestamp" to timestamp,
        "left" to left.canonical() + ("signature" to left.signature),
        "right" to right.canonical() + ("signature" to right.signature),
    )

    fun signingBytes(): ByteArray = Canonical.bytes(
        mapOf(
            "exchange_id" to id,
            "timestamp" to timestamp,
            "left" to left.canonical(),
            "right" to right.canonical(),
        )
    )

    val complete: Boolean get() = left.signature != null && right.signature != null

    fun side(iouId: String): ExchangeSide? = when {
        left.holds(iouId) -> left
        right.holds(iouId) -> right
        else -> null
    }

    fun other(iouId: String): ExchangeSide? = when {
        left.holds(iouId) -> right
        right.holds(iouId) -> left
        else -> null
    }
}

enum class Status { ACTIVE, REDEEMED }

/** What a valid chain says right now. */
data class IouState(
    val debtor: String,
    val holder: String,
    val status: Status,
    val length: Int,
    val headHash: String,
)
