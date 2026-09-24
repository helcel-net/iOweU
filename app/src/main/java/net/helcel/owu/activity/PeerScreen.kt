package net.helcel.owu.activity

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.Card
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.LocalContentColor
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import net.helcel.owu.R
import net.helcel.owu.ble.Ble
import net.helcel.owu.helper.Gates
import net.helcel.owu.helper.Locator
import net.helcel.owu.helper.toast
import net.helcel.owu.ledger.Status
import net.helcel.owu.ledger.Verifier
import net.helcel.owu.peer.PeerManager
import net.helcel.owu.peer.PeerSession
import net.helcel.owu.store.Contact
import net.helcel.owu.store.Repo

/** One person, once picked: the table between us. Opening this screen opens
 *  the connection and leaving it hangs up again. */
@Composable
fun PeerScreen(nav: NavHostController, beaconHex: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by PeerManager.state.collectAsState()
    val session = st.peers[beaconHex]
    if (session == null) {
        // They walked away while we were looking at them.
        LaunchedEffect(Unit) { nav.up() }
        return
    }
    val ss by session.state.collectAsState()
    val ious by Repo.store.ious.collectAsState()
    val contacts by Repo.store.contacts.collectAsState()
    val templates by Repo.store.templates.collectAsState()
    val peer = ss.peer
    val known = peer != null && contacts.any { it.publicKey == peer.key }
    // Until the handshake finishes there is no identity to read a name from,
    // and you arrived here by tapping somebody by name: say their name. The
    // beacon is derived from their key, so this is the contact you picked,
    // not a guess - it is only the *proof* that is still a second away.
    val who = peerLabel(peer, known).ifBlank {
        contacts.firstOrNull { Ble.beacon(it.publicKey).contentEquals(session.beacon) }?.name.orEmpty()
    }

    var connecting by remember { mutableStateOf(true) }
    var linkError by remember { mutableStateOf(false) }
    LaunchedEffect(beaconHex) {
        connecting = true
        linkError = !PeerManager.select(session.beacon)
        connecting = false
    }
    // Once we are known to each other, tell them we are here: they get a
    // prompt wherever they are, rather than having to guess that somebody is
    // standing at a table waiting for them.
    LaunchedEffect(beaconHex, ss.phase) {
        if (ss.phase == PeerSession.Phase.READY) session.invite()
    }
    DisposableEffect(beaconHex) {
        onDispose { PeerManager.release(session.beacon) }
    }

    var addName by remember { mutableStateOf<String?>(null) }
    var pickMine by remember { mutableStateOf(false) }
    // OwUs on the table we are not at the place of, so the table can say so
    // in red. It is never a bar - the one who owes it decides.
    var outOfPlace by remember { mutableStateOf(emptySet<String>()) }

    val ready = ss.phase == PeerSession.Phase.READY && !connecting
    // Verified, connected and nothing in the way: the state the tick stands for.
    val settled = ready && !linkError
    // Everything I could put down, minted ious for this table included: the
    // picker shows what is already down as chosen rather than hiding it.
    val mineHeld = remember(ious) {
        ious.values.filter {
            Verifier.verify(it).stateOrNull?.let { s -> s.holder == Repo.me && s.status == Status.ACTIVE } == true
        }
    }
    // Putting ious back on the table with the person who owes them *is*
    // redeeming: they go home and they close them.
    val redeeming = ss.table.mine.isNotEmpty() &&
            ss.table.mine.all { Verifier.verify(it).stateOrNull?.debtor == peer?.key }

    // A redeem begun from the OwU itself: put it down as soon as this table
    // can take it, so the holder does not have to find it again in the picker.
    LaunchedEffect(ready, peer?.key) {
        val key = peer?.key
        if (ready && key != null) {
            TradeIntent.takeOffer(key)?.let { offer ->
                Repo.store.ious.value[offer.iouId]?.let { session.put(held = listOf(it)) }
            }
        }
    }

    val tableIds = (ss.table.mine + ss.table.theirs).joinToString(",") { it.id }
    LaunchedEffect(tableIds) {
        outOfPlace = (ss.table.mine + ss.table.theirs)
            .filter { Gates.outOfPlace(context, it) }
            .map { it.id }
            .toSet()
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) context.toast(context.getString(R.string.geo_permission_denied))
    }

    // --- dialogs -----------------------------------------------------------

    addName?.let { name ->
        AlertDialog(
            onDismissRequest = { addName = null },
            title = { Text(stringResource(R.string.contact_name_this)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = name, onValueChange = { addName = it },
                        label = { Text(stringResource(R.string.name)) }, singleLine = true
                    )
                    Text(
                        stringResource(R.string.contact_name_hint),
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    Repo.store.putContact(Contact(peer!!.key, name.trim()))
                    addName = null
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { addName = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (pickMine) TablePicker(
        templates = templates.values.sortedByDescending { it.createdAt },
        held = mineHeld,
        onTable = ss.table.mine,
        onDismiss = { pickMine = false },
        onWrite = {
            pickMine = false
            TradeIntent.pending = TradeIntent.Pending(peer!!.key)
            nav.navigate("issue")
        },
        onPut = { ious, mint ->
            pickMine = false
            session.put(held = ious, mint = mint)
            // Judging a place gate needs the permission; ask while there is
            // still time, not at the moment of saying yes.
            val placed = ious.any { it.metadata.geoloc != null } || mint.any { it.geoloc != null }
            if (placed && !Locator.hasPermission(context)) locationPermission.launch(Locator.PERMISSION)
        },
    )

    // --- screen ------------------------------------------------------------

    Scaffold(
        topBar = {
            TopAppBar(
                // The tick belongs beside whoever they are, which is the name
                // when you have one and the key when you do not - and that is
                // the title either way.
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(who.ifBlank { stringResource(R.string.peer_unknown) })
                        if (settled) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = stringResource(R.string.peer_verified),
                                tint = LocalContentColor.current,
                                modifier = Modifier.padding(start = 8.dp).size(20.dp),
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { nav.up() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
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
            // The key under the name, when the name is not the key already.
            peerFingerprint(peer, who)?.let {
                Text(
                    it, style = MaterialTheme.typography.caption,
                    fontFamily = FontFamily.Monospace, color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                )
            }
            impersonating(peer)?.let {
                Text(
                    it, style = MaterialTheme.typography.body2, color = MaterialTheme.colors.error,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            // Only for somebody you have not named yet: this is the one place
            // a peer met over the air can become a contact. Renaming one you
            // already have belongs on the identity screen, where they live.
            if (peer != null && !known) {
                OutlinedButton(
                    onClick = { addName = peer.name },
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text(stringResource(R.string.contact_name_this))
                }
            }
            // Only while something is still in the way; once it is settled the
            // tick above says so and this row goes.
            if (!settled) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    if (connecting || ss.phase == PeerSession.Phase.HANDSHAKE) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(
                        when {
                            linkError -> stringResource(R.string.peer_no_link)
                            connecting -> stringResource(R.string.peer_connecting)
                            ss.phase == PeerSession.Phase.GONE -> stringResource(R.string.peer_gone)
                            else -> stringResource(R.string.peer_verifying)
                        },
                        style = MaterialTheme.typography.body2,
                        color = if (linkError || ss.phase == PeerSession.Phase.GONE) MaterialTheme.colors.error
                        else MaterialTheme.colors.primary,
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), elevation = 2.dp) {
                TradeTable(
                    table = ss.table,
                    theirName = who,
                    theirKey = peer?.key,
                    enabled = ready,
                    redeeming = redeeming,
                    outOfPlace = outOfPlace,
                    onPut = { pickMine = true },
                    onAccept = { session.accept() },
                    modifier = Modifier.padding(12.dp),
                )
            }

            if (ss.waiting != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Text(
                        stringResource(R.string.peer_waiting, ss.waiting ?: ""), style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f), modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { session.stopWaiting() }) { Text(stringResource(R.string.peer_stop_waiting)) }
                }
            }

            if (ss.log.isNotEmpty()) {
                Divider(modifier = Modifier.padding(vertical = 12.dp))
                ss.log.takeLast(8).forEach {
                    Text(
                        it,
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}
