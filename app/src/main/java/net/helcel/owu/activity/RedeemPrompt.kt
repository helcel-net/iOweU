package net.helcel.owu.activity

import androidx.compose.foundation.layout.Column
import androidx.compose.material.AlertDialog
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import net.helcel.owu.R
import net.helcel.owu.ledger.Iou
import net.helcel.owu.ledger.Verifier
import net.helcel.owu.peer.PeerManager
import net.helcel.owu.peer.PeerMessage
import net.helcel.owu.peer.PeerSession
import net.helcel.owu.store.Repo

/**
 * Somebody is asking to redeem a promise of yours. It is the table like
 * anything else, but with nothing to choose: they have put it down and said
 * yes, so the only question is whether to accept. Asked here, wherever the
 * debtor is, rather than on a trade screen holding one thing.
 */
@Composable
fun RedeemPrompt() {
    val st by PeerManager.state.collectAsState()
    // Asks turned down stay down until the table changes.
    val refused = remember { mutableStateListOf<String>() }

    var asking: Pair<PeerSession, List<Iou>>? = null
    val live = mutableListOf<String>()
    st.peers.forEach { (hex, session) ->
        key(hex) {
            val ss by session.state.collectAsState()
            val theirs = ss.table.theirs
            // Theirs alone, all of them mine, their yes given: a redemption.
            val isAsk = theirs.isNotEmpty() && ss.table.mine.isEmpty() &&
                    ss.table.theyAccepted && !ss.table.accepted && !ss.table.conflict &&
                    theirs.all { Verifier.verify(it).stateOrNull?.debtor == Repo.me }
            if (isAsk) {
                live += ticket(hex, theirs)
                if (ticket(hex, theirs) !in refused && asking == null) asking = session to theirs
            }
        }
    }
    // A refusal lasts as long as the ask it refused. Held for good, "Not now"
    // would swallow every later attempt at the same promise.
    LaunchedEffect(live.joinToString("|")) { refused.retainAll(live) }

    asking?.let { (session, ious) ->
        // The key checks the ious, so an ask stands with or without a name.
        val who = session.state.value.peer?.let { Repo.nameOf(it.key) } ?: stringResource(R.string.peer_unknown)
        AlertDialog(
            onDismissRequest = { refused += ticket(net.helcel.owu.ble.Ble.hex(session.beacon), ious) },
            title = { Text(stringResource(R.string.redeem_ask, who)) },
            text = {
                Column {
                    Text(bundleLine(ious), style = MaterialTheme.typography.h6)
                    ious.firstOrNull()?.let { MetaGates(it.metadata, homecoming = true) }
                }
            },
            confirmButton = {
                TextButton(onClick = { session.accept() }) { Text(stringResource(R.string.action_accept)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    refused += ticket(net.helcel.owu.ble.Ble.hex(session.beacon), ious)
                    // Say so: their side clears the ask with ours, so they get
                    // the Redeem button back instead of waiting on you.
                    session.decline(PeerMessage.Asked.TABLE)
                }) { Text(stringResource(R.string.redeem_later)) }
            },
        )
    }
}

/** What was refused: this peer asking for exactly these ious. */
private fun ticket(hex: String, ious: List<Iou>): String =
    hex + ious.map { it.id }.sorted().joinToString(",")
