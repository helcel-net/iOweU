package net.helcel.owu.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import net.helcel.owu.R
import net.helcel.owu.ble.Ble
import net.helcel.owu.peer.PeerManager
import net.helcel.owu.peer.PeerSession
import net.helcel.owu.helper.Gates
import net.helcel.owu.helper.formatTime
import net.helcel.owu.ledger.Block
import net.helcel.owu.ledger.Iou
import net.helcel.owu.ledger.Status
import net.helcel.owu.ledger.TimeGate
import net.helcel.owu.ledger.Verdict
import net.helcel.owu.ledger.Verifier
import net.helcel.owu.store.Repo

/** One OwU: what it is, where it stands, its history - and, when somebody
 *  else owes it, the button that asks them to redeem it. */
@Composable
fun IouDetailScreen(nav: NavHostController, id: String) {
    val context = LocalContext.current
    val ious by Repo.store.ious.collectAsState()
    // Subscribed, never read: Repo.nameOf() is a plain lookup, so without this
    // nothing here would notice a contact being renamed.
    @Suppress("UNUSED_VARIABLE") val contacts by Repo.store.contacts.collectAsState()
    val iou = ious[id]
    if (iou == null) {
        // Gone. Leaving is an effect, not part of drawing: popping from
        // composition ran again next frame and took the screen underneath too,
        // leaving an empty NavHost and a blank screen.
        LaunchedEffect(id) { nav.up() }
        return
    }
    val verdict = remember(iou) { Verifier.verify(iou) }
    val state = verdict.stateOrNull
    val me = Repo.me

    var showDelete by remember { mutableStateOf(false) }

    // --- dialogs -----------------------------------------------------------

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text(stringResource(R.string.delete)) },
            text = { Text(stringResource(R.string.delete_iou_confirm)) },
            confirmButton = {
                // Removing is enough: the screen sees it gone and leaves.
                // Popping here too was the second pop that emptied the stack.
                TextButton(onClick = { Repo.store.remove(iou.id); showDelete = false }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    // --- screen ------------------------------------------------------------

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(iou.metadata.title) },
                navigationIcon = {
                    IconButton(onClick = { nav.up() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = { showDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .background(MaterialTheme.colors.background)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            if (state == null) {
                val v = verdict as Verdict.Invalid
                Text(stringResource(R.string.invalid_chain, v.sequence, v.reason), color = MaterialTheme.colors.error)
                return@Column
            }

            // What was written with it, before the bookkeeping: the part read.
            iou.metadata.description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.body1,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                )
            }
            Field(stringResource(R.string.field_status), statusLabel(state.status))
            Field(stringResource(R.string.role_debtor), Repo.nameOf(state.debtor), verified = state.debtor)
            Field(stringResource(R.string.role_holder), Repo.nameOf(state.holder))
            if (iou.metadata.hasWindow) {
                Field(
                    stringResource(R.string.field_window),
                    listOfNotNull(
                        iou.metadata.notBefore?.let { stringResource(R.string.window_from, formatTime(it)) },
                        iou.metadata.notAfter?.let { stringResource(R.string.window_until, formatTime(it)) },
                    ).joinToString(" "),
                )
            }
            iou.metadata.geoloc?.let { g ->
                Field(
                    stringResource(R.string.field_geoloc),
                    (g.label?.let { "$it · " } ?: "") + "%.5f, %.5f · %d m".format(g.latitude, g.longitude, g.radiusM),
                )
            }

            // Redeeming is handing it back to whoever wrote it. Your own
            // promise closes itself on coming home, so this is only ever
            // somebody else's, in your hands.
            if (state.status == Status.ACTIVE && state.holder == me && state.debtor != me) {
                // Greyed when late, early or elsewhere, and still pressable:
                // none of that decides anything. Only the one who wrote it can.
                var away by remember(iou.id) { mutableStateOf(false) }
                LaunchedEffect(iou.id) { away = Gates.outOfPlace(context, iou) }
                val off = iou.metadata.timeGate() != TimeGate.Open || away

                // An ask already out: show it rather than a button that would
                // only ask again. Silence is the one thing not to do.
                val onAir by PeerManager.state.collectAsState()
                val session = onAir.peers[Ble.beaconHex(state.debtor)]
                val ask = askOutstanding(session, iou.id)
                // Asked for, kept until it is on their table: until then they
                // are still to be found. Its own state, and it must look like
                // one, or the button appears to have done nothing.
                val parked = TradeIntent.offer?.takeIf { it.iouId == iou.id } != null
                if (ask != null || parked) {
                    AskStatus(
                        who = Repo.nameOf(state.debtor),
                        looking = ask == null,
                        undelivered = ask?.undelivered != null,
                        onStop = {
                            TradeIntent.offer = null
                            session?.put()
                        },
                    )
                } else Button(
                    // Earshot is not this button's business: the ask is written
                    // down and [PendingAsk] finds them, connects and sends it.
                    // Nowhere to go, nothing to arrange.
                    onClick = { TradeIntent.offer = TradeIntent.Offer(state.debtor, iou.id) },
                    colors = if (!off) ButtonDefaults.buttonColors() else ButtonDefaults.buttonColors(
                        backgroundColor = MaterialTheme.colors.onSurface.copy(alpha = 0.12f),
                        contentColor = MaterialTheme.colors.onSurface.copy(alpha = 0.38f),
                    ),
                    elevation = if (off) ButtonDefaults.elevation(0.dp, 0.dp, 0.dp) else ButtonDefaults.elevation(),
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                ) { Text(stringResource(R.string.action_redeem)) }
            }

            Text(
                stringResource(R.string.history),
                style = MaterialTheme.typography.h6,
                modifier = Modifier.padding(top = 24.dp, bottom = 4.dp),
            )
            Divider()
            // Handing an OwU back to its author and the author closing it are
            // one thing happening - redeeming - so the pair is shown once.
            iou.chain.filterIndexed { i, block ->
                !(block is Block.Transfer && block.transferee == debtorOf(iou) && iou.chain.getOrNull(i + 1) is Block.Redeemed)
            }.forEach { block ->
                HistoryRow(iou, block)
                Divider()
            }
            Text(
                iou.id,
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

@Composable
private fun Field(label: String, value: String, verified: String? = null) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.size(width = 96.dp, height = 20.dp),
        )
        Text(value, style = MaterialTheme.typography.body1, fontWeight = FontWeight.Medium)
        verified?.let { KnownMark(it) }
    }
}

/** The one who made the promise, and so the only one who can close it. */
private fun debtorOf(iou: Iou): String? = (iou.chain.firstOrNull() as? Block.Issue)?.debtor

@Composable
private fun HistoryRow(iou: Iou, block: Block) {
    // A promise is always written to oneself, so there is no "to" worth
    // saying; and giving it back to its author is redeeming it, which is the
    // name this and the closing that follows it go by.
    val debtor = debtorOf(iou)
    val handedBack = iou.chain.getOrNull(block.sequence - 1) as? Block.Transfer
    val line = when (block) {
        is Block.Issue -> stringResource(R.string.hist_issued, Repo.nameOf(block.debtor))
        // A swap says so; a plain hand-over reads by where it went.
        is Block.Transfer -> when {
            block.agreement != null ->
                stringResource(R.string.hist_exchanged, Repo.nameOf(block.transferor), Repo.nameOf(block.transferee))

            block.transferee == debtor -> stringResource(R.string.hist_redeemed, Repo.nameOf(block.transferor))
            else ->
                stringResource(R.string.hist_transferred, Repo.nameOf(block.transferor), Repo.nameOf(block.transferee))
        }
        // Normally this closes a hand-back and takes its name; a chain that
        // reaches this state another way still gets a line of its own.
        is Block.Redeemed ->
            if (handedBack != null && handedBack.transferee == debtor)
                stringResource(R.string.hist_redeemed, Repo.nameOf(handedBack.transferor))
            else stringResource(R.string.hist_closed, Repo.nameOf(block.debtor))
    }
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(line, style = MaterialTheme.typography.body1)
        Text(
            "#${block.sequence} · ${formatTime(block.timestamp)}",
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
        )
    }
}

/**
 * The state of an ask already sent to this OwU's debtor, or null when there
 * is none: my side of their table holds exactly this OwU, and I have said
 * yes, so the only thing left is their answer.
 */
@Composable
private fun askOutstanding(session: PeerSession?, iouId: String): PeerSession.State? {
    if (session == null) return null
    val ss by session.state.collectAsState()
    val mine = ss.table.mine
    return ss.takeIf { mine.size == 1 && mine[0].id == iouId && ss.table.accepted }
}

/** Looking for them, waiting on them, or unable to reach them - and a way to stop any of it. */
@Composable
private fun AskStatus(who: String, looking: Boolean, undelivered: Boolean, onStop: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (looking) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(12.dp))
            }
            Text(
                stringResource(
                    when {
                        looking -> R.string.redeem_looking
                        undelivered -> R.string.redeem_unreachable
                        else -> R.string.redeem_waiting
                    },
                    who,
                ),
                style = MaterialTheme.typography.body1,
                color = if (undelivered && !looking) MaterialTheme.colors.error else MaterialTheme.colors.onSurface,
            )
        }
        TextButton(onClick = onStop, modifier = Modifier.padding(top = 4.dp)) {
            Text(stringResource(R.string.redeem_stop))
        }
    }
}
