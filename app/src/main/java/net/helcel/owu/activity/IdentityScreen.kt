package net.helcel.owu.activity

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Card
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import net.helcel.owu.R
import net.helcel.owu.ble.Ble
import net.helcel.owu.crypto.Keys
import net.helcel.owu.peer.PeerManager
import net.helcel.owu.peer.PeerSession
import net.helcel.owu.helper.IdentityCard
import net.helcel.owu.helper.clipboardText
import net.helcel.owu.helper.copyToClipboard
import net.helcel.owu.helper.toast
import net.helcel.owu.store.Contact
import net.helcel.owu.store.Repo

@Composable
fun IdentityScreen(nav: NavHostController) {
    val context = LocalContext.current
    val contacts by Repo.store.contacts.collectAsState()
    val onAir by PeerManager.state.collectAsState()
    var name by remember { mutableStateOf(Repo.myName(context)) }
    val card = remember(name) { IdentityCard(name = name.trim(), key = Repo.me).encode() }

    // A scanned or pasted card is confirmed (and named) before it is kept.
    var pending by remember { mutableStateOf<IdentityCard?>(null) }
    var showManual by remember { mutableStateOf(false) }
    // A name is yours to choose and yours to change; the pencil on the row
    // opens it, since the row itself now goes to their table.
    var renaming by remember { mutableStateOf<Contact?>(null) }
    // Passing one on: the same card this screen shows for yourself, for them.
    var showing by remember { mutableStateOf<Contact?>(null) }

    fun offer(text: String?) {
        val c = text?.let { IdentityCard.decode(it) }
            ?: text?.trim()?.takeIf { Keys.decode(it) != null }?.let { IdentityCard(name = "", key = it) }
        if (c == null) context.toast(context.getString(R.string.contact_unreadable))
        else if (c.key == Repo.me) context.toast(context.getString(R.string.contact_is_you))
        else pending = c
    }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result -> result.contents?.let { offer(it) } }
    fun scan() = scanner.launch(ScanOptions().apply {
        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        setPrompt(context.getString(R.string.scan_prompt))
        setBeepEnabled(false)
        // Held the way the app is held. Unlocked, it follows the sensor and
        // turns sideways while you are lining a code up.
        setOrientationLocked(true)
    })

    pending?.let { c ->
        var contactName by remember(c) { mutableStateOf(c.name) }
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.contact_add)) },
            text = {
                Column {
                    Text(Keys.fingerprint(c.key), fontFamily = FontFamily.Monospace)
                    OutlinedTextField(
                        value = contactName, onValueChange = { contactName = it },
                        label = { Text(stringResource(R.string.name)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = contactName.isNotBlank(), onClick = {
                    Repo.store.putContact(Contact(c.key, contactName.trim()))
                    pending = null
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    renaming?.let { c ->
        var name by remember(c) { mutableStateOf(c.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.contact_rename)) },
            text = {
                Column {
                    Text(Keys.fingerprint(c.publicKey), fontFamily = FontFamily.Monospace)
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text(stringResource(R.string.name)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    Repo.store.putContact(Contact(c.publicKey, name.trim()))
                    renaming = null
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    // A contact's card for somebody else to scan, the same shape as your own.
    // The name travels as a suggestion; the key is what checks their signatures.
    //
    // A plain Dialog, not an AlertDialog: M2's lays its text slot out by first
    // and last text baseline, and a QR code has neither, so a slot starting
    // with one measures to a negative height and throws.
    showing?.let { c ->
        Dialog(onDismissRequest = { showing = null }) {
            Card(shape = MaterialTheme.shapes.medium, elevation = 8.dp) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp),
                ) {
                    Text(c.name, style = MaterialTheme.typography.h6)
                    QrCode(
                        remember(c) { IdentityCard(name = c.name, key = c.publicKey).encode() },
                        modifier = Modifier.padding(top = 16.dp).size(240.dp),
                    )
                    Text(
                        Keys.fingerprint(c.publicKey),
                        style = MaterialTheme.typography.caption,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    TextButton(
                        onClick = { showing = null },
                        modifier = Modifier.align(Alignment.End).padding(top = 8.dp),
                    ) { Text(stringResource(R.string.ok)) }
                }
            }
        }
    }
    if (showManual) {
        var key by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showManual = false },
            title = { Text(stringResource(R.string.contact_add_manually)) },
            text = {
                OutlinedTextField(
                    value = key, onValueChange = { key = it },
                    label = { Text(stringResource(R.string.field_key)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = { showManual = false; offer(key) }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showManual = false; offer(context.clipboardText()) }) {
                    Text(stringResource(R.string.contact_from_clipboard))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.identity_title)) },
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
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; Repo.setMyName(context, it.trim()) },
                label = { Text(stringResource(R.string.field_your_name)) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            QrCode(card, modifier = Modifier.padding(top = 16.dp).size(240.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text(
                    Keys.fingerprint(Repo.me),
                    style = MaterialTheme.typography.h6,
                    fontFamily = FontFamily.Monospace,
                )
                IconButton(onClick = {
                    context.copyToClipboard("iou key", Repo.me)
                    context.toast(context.getString(R.string.copied))
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.action_copy_key))
                }
            }
            // The two ways to become a contact, at the head of the list they
            // join rather than below however many people you already know.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 4.dp),
            ) {
                Text(
                    stringResource(R.string.contacts),
                    style = MaterialTheme.typography.h6,
                    modifier = Modifier.weight(1f),
                )
                // A camera and a plus. A QR glyph here was indistinguishable
                // from the one on every row below, which *shows* a code rather
                // than reads one; a key says "key" only to us.
                IconButton(onClick = { scan() }) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = stringResource(R.string.contact_scan))
                }
                IconButton(onClick = { showManual = true }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.contact_add_manually))
                }
            }
            Divider()
            if (contacts.isEmpty()) {
                Text(
                    stringResource(R.string.no_contacts),
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                )
            }
            // Who is on the air, read once: the rows say it and the sort uses
            // it, and two readings of the same thing drift apart.
            val here = onAir.peers.mapNotNull { (hex, session) ->
                key(hex) {
                    val ss by session.state.collectAsState()
                    hex.takeIf { ss.phase != PeerSession.Phase.GONE }
                }
            }.toSet()
            // Here now first, then the rest, alphabetical within each, so a
            // name does not wander far when somebody arrives or leaves.
            contacts.sortedWith(
                compareByDescending<Contact> { Ble.beaconHex(it.publicKey) in here }
                    .thenBy { it.name.lowercase() }
            ).forEach { c ->
                ContactRow(
                    contact = c,
                    here = Ble.beaconHex(c.publicKey) in here,
                    onTrade = { nav.navigate("peer/${Ble.beaconHex(c.publicKey)}") },
                    onShare = { showing = c },
                    onRename = { renaming = c },
                    onDelete = { Repo.store.removeContact(c.publicKey) },
                )
                Divider()
            }
        }
    }
}

/**
 * One person you have named: name, key, whether they are in earshot, and the
 * button that opens the table with them.
 *
 * The only door to a trade: you cannot hand a promise to somebody you have not
 * named. Redeeming is not a trade and does not come through here - what
 * somebody owes you is asked for from the OwU itself, on any screen.
 */
@Composable
private fun ContactRow(
    contact: Contact,
    here: Boolean,
    onTrade: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onRename)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            // A dot beside the name rather than a line of prose under it:
            // being here is a property of the person, and it reads at a
            // glance down a list where three lines apiece did not. Lit in the
            // theme's own colour when they are on the air, all but out when
            // they are not - and it keeps its words for the screen reader.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(contact.name, style = MaterialTheme.typography.subtitle1, fontWeight = FontWeight.Bold)
                Icon(
                    Icons.Default.Circle,
                    contentDescription = stringResource(if (here) R.string.contact_here else R.string.contact_away),
                    tint = if (here) MaterialTheme.colors.primary
                    else MaterialTheme.colors.onSurface.copy(alpha = 0.2f),
                    modifier = Modifier.padding(start = 8.dp).size(10.dp),
                )
            }
            Text(
                Keys.fingerprint(contact.publicKey),
                style = MaterialTheme.typography.caption, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
            )
        }
        // Greyed and dead until they are actually on the air: a table needs
        // both of you, and there is nothing useful to open without them.
        IconButton(onClick = onTrade, enabled = here) {
            Icon(
                Icons.Default.SwapHoriz,
                contentDescription = stringResource(R.string.contact_trade),
                tint = if (here) MaterialTheme.colors.primary
                else MaterialTheme.colors.onSurface.copy(alpha = 0.3f),
            )
        }
        IconButton(onClick = onShare) {
            Icon(Icons.Default.QrCode2, contentDescription = stringResource(R.string.contact_share))
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
        }
    }
}
