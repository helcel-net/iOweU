package net.helcel.owu.activity

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** What a screen was opened for, on the way to somebody's table. Gone once claimed. */
object TradeIntent {
    /** The new promise being written belongs on this peer's table. */
    data class Pending(val peerKey: String)

    // Compose state, not plain fields: a plain var gives screens no reason to look again.
    var pending: Pending? by mutableStateOf(null)

    fun take(): Pending? = pending.also { pending = null }

    /** An OwU to put down once the table with [peerKey] opens: how a redeem
     *  begun from the OwU finds its table without hunting in the picker. */
    data class Offer(val peerKey: String, val iouId: String)

    var offer: Offer? by mutableStateOf(null)

    /** Claims the waiting offer, if it is for this peer. */
    fun takeOffer(peerKey: String): Offer? =
        offer?.takeIf { it.peerKey == peerKey }?.also { offer = null }
}
