package net.helcel.owu.activity

import androidx.compose.material.AlertDialog
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import net.helcel.owu.R
import net.helcel.owu.peer.PeerManager
import net.helcel.owu.peer.PeerMessage
import net.helcel.owu.peer.PeerSession
import net.helcel.owu.store.Repo

/**
 * Somebody has opened a table with you; without this they would have to be on
 * the right screen at the right moment to find out. Same shape as
 * [RedeemPrompt]. Only from people you have named: trading is contacts-only,
 * and a dialog any passer-by could raise on your phone is a nuisance.
 */
@Composable
fun TradePrompt(nav: NavHostController) {
    val st by PeerManager.state.collectAsState()
    val contacts by Repo.store.contacts.collectAsState()
    val route = nav.currentBackStackEntryAsState().value?.destination?.route

    var asking: Pair<String, PeerSession>? = null
    st.peers.forEach { (hex, session) ->
        key(hex) {
            val ss by session.state.collectAsState()
            val known = ss.peer?.key?.let { k -> contacts.any { it.publicKey == k } } == true
            // Already at their table: being there is the answer.
            val attending = route == "peer/{beacon}" &&
                    nav.currentBackStackEntry?.arguments?.getString("beacon") == hex
            if (ss.invited && attending) LaunchedEffect(hex) { session.inviteAnswered() }
            else if (ss.invited && known && asking == null) asking = hex to session
        }
    }

    asking?.let { (hex, session) ->
        val who = session.state.value.peer?.let { Repo.nameOf(it.key) } ?: stringResource(R.string.peer_unknown)
        AlertDialog(
            // Dismissing is not a refusal to report; only "Not now" tells them.
            onDismissRequest = { session.inviteAnswered() },
            title = { Text(stringResource(R.string.trade_ask, who)) },
            confirmButton = {
                TextButton(onClick = {
                    session.inviteAnswered()
                    nav.navigate("peer/$hex")
                }) { Text(stringResource(R.string.trade_accept)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    session.inviteAnswered()
                    session.decline(PeerMessage.Asked.INVITE)
                }) { Text(stringResource(R.string.redeem_later)) }
            },
        )
    }
}
