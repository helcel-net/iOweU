package net.helcel.owu.activity

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import net.helcel.owu.R
import net.helcel.owu.ble.Ble
import net.helcel.owu.helper.toast
import net.helcel.owu.ledger.Status
import net.helcel.owu.ledger.Verifier
import net.helcel.owu.peer.PeerManager
import net.helcel.owu.peer.PeerMessage
import net.helcel.owu.peer.PeerSession
import net.helcel.owu.store.Repo

/**
 * Every ask to redeem, from the tap onward. The button writes down who is
 * asked for what; finding them, connecting, the handshake and the sending all
 * happen here at the app root, whatever screen the user went on to. The other
 * phone need not have been found, or even be switched on.
 *
 * The ask keeps until it is *on their table*, not until sent: two phones can
 * disagree about having been introduced, and putting that right costs the
 * table it carried.
 */
@Composable
fun PendingAsk() {
    val context = LocalContext.current
    val waiting = TradeIntent.offer

    // A "no" ends it, or the loop below re-asks every few seconds and the two
    // phones refuse each other for as long as they share a room.
    LaunchedEffect(Unit) {
        PeerManager.outcomes.collect { outcome ->
            if (outcome.declined == PeerMessage.Asked.TABLE && outcome.peer == TradeIntent.offer?.peerKey) {
                TradeIntent.offer = null
            }
        }
    }

    // Keyed on *what* is asked, not the session: a peer already in the list
    // never changes that object, so an effect keyed on it never runs.
    LaunchedEffect(waiting?.peerKey, waiting?.iouId) {
        val offer = waiting ?: return@LaunchedEffect
        val hex = Ble.beaconHex(offer.peerKey)
        var told = false
        while (true) {
            if (TradeIntent.offer != offer) return@LaunchedEffect
            // Redeemed, given away or deleted: not ours to ask about any more.
            val iou = Repo.store.ious.value[offer.iouId]
            val state = iou?.let { Verifier.verify(it).stateOrNull }
            if (state == null || state.holder != Repo.me || state.status != Status.ACTIVE) {
                TradeIntent.offer = null
                return@LaunchedEffect
            }
            val session = PeerManager.state.value.peers[hex]
            if (session != null && !onTheTable(session, offer.iouId)) {
                val ready = withTimeoutOrNull(ASK_WAIT_MS) {
                    PeerManager.select(session.beacon) &&
                            session.state.first { it.phase == PeerSession.Phase.READY }.let { true }
                } == true
                if (ready) {
                    session.offer(listOf(iou))
                    // Once per ask: the loop may send twice, the user asked once.
                    if (!told) {
                        context.toast(context.getString(R.string.redeem_asked, Repo.nameOf(offer.peerKey)))
                        told = true
                    }
                }
            }
            delay(RETRY_MS)
        }
    }
}

/** Down on our side of their table, and accepted: the ask is with them. */
private fun onTheTable(session: PeerSession, iouId: String): Boolean {
    val table = session.state.value.table
    return table.mine.size == 1 && table.mine[0].id == iouId && table.accepted
}

/** Long enough for two phones to say hello over a fresh connection. */
private const val ASK_WAIT_MS = 12_000L

/** How often to look again. Short: usually the peer is right there and simply
 *  had not been heard from when the button was pressed. */
private const val RETRY_MS = 3_000L
