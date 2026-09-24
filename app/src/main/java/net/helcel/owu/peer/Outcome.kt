package net.helcel.owu.peer

import net.helcel.owu.ledger.Iou

/**
 * What a finished table amounted to, for whatever says so on screen. One per
 * protocol step, not per event: a redemption reaches the debtor as a gift and
 * a receipt together, and that is one thing that happened.
 */
data class Outcome(
    /** Their public key, when the handshake got that far. */
    val peer: String?,
    val gave: List<Iou> = emptyList(),
    val got: List<Iou> = emptyList(),
    val redeemed: List<Iou> = emptyList(),
    /** Or their "no", to being asked to the table or to what was on it. */
    val declined: PeerMessage.Asked? = null,
) {
    val isEmpty: Boolean
        get() = gave.isEmpty() && got.isEmpty() && redeemed.isEmpty() && declined == null
}
