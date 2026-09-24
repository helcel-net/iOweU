package net.helcel.owu.activity

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.LocalContentColor
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import androidx.navigation.NavHostController
import net.helcel.owu.R
import net.helcel.owu.crypto.Keys
import net.helcel.owu.helper.IdentityCard
import net.helcel.owu.helper.formatTime
import net.helcel.owu.peer.PeerEngine
import net.helcel.owu.ledger.Iou
import net.helcel.owu.ledger.Metadata
import net.helcel.owu.ledger.Status
import net.helcel.owu.ledger.TimeGate
import net.helcel.owu.ledger.Verifier
import net.helcel.owu.store.Repo
import net.helcel.owu.store.Template
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

// Pieces shared by more than one screen.

@Composable
fun statusLabel(status: Status): String = when (status) {
    Status.ACTIVE -> stringResource(R.string.status_active)
    Status.REDEEMED -> stringResource(R.string.status_redeemed)
}

@Composable
fun peerLabel(peer: IdentityCard?, known: Boolean): String = when {
    peer == null -> ""
    known -> Repo.nameOf(peer.key)
    else -> peer.name.ifBlank { Keys.fingerprint(peer.key) }
}

/** Set when a peer calls itself a name you know somebody else by. Names are
 *  self-asserted, keys are not: the one place a stranger can try to pass. */
@Composable
fun impersonating(peer: IdentityCard?): String? {
    if (peer == null || peer.name.isBlank()) return null
    val contacts by Repo.store.contacts.collectAsState()
    val clash = contacts.firstOrNull { it.name.equals(peer.name.trim(), ignoreCase = true) && it.publicKey != peer.key }
    return clash?.let { stringResource(R.string.peer_name_clash, it.name) }
}

/** A tick beside a name whose key we keep: that person's promise, not merely
 *  a valid signature by a stranger. A missing tick is the whole message. */
@Composable
fun KnownMark(publicKey: String, modifier: Modifier = Modifier) {
    if (!Repo.knows(publicKey)) return
    Icon(
        Icons.Default.Check,
        contentDescription = stringResource(R.string.in_your_contacts),
        tint = MaterialTheme.colors.primary,
        modifier = modifier.padding(start = 4.dp).size(14.dp),
    )
}

/** The key under a name, unless the name *is* the key already. */
@Composable
fun peerFingerprint(peer: IdentityCard?, label: String): String? =
    peer?.let { Keys.fingerprint(it.key) }?.takeIf { it != label }

/**
 * What to put on the table: nothing, promises minted from templates, or OwUs
 * you hold - any number, in one bundle.
 *
 * A sheet, not a dialog: the list has no known length, and as a dialog it grew
 * to fill the screen badly. Writing a new promise leads, being the one entry
 * that is an action; templates follow, the common case being the same beer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TablePicker(
    templates: List<Template>,
    held: List<Iou>,
    /** What is already on my side, so the sheet opens on the bundle as it stands. */
    onTable: List<Iou>,
    onDismiss: () -> Unit,
    onWrite: () -> Unit,
    /** The bundle as chosen: ious I hold, and one mint per template copy. */
    onPut: (List<Iou>, List<Metadata>) -> Unit,
) {
    // A minted beer on the table counts as one beer of its template, so
    // reopening the sheet shows what is down rather than starting empty.
    val down = remember(onTable) { onTable.map { it.id }.toSet() }
    val counts = remember(onTable) { mutableStateMapOf<String, Int>() }
    val chosen = remember(onTable) { mutableStateListOf<String>().apply { addAll(down) } }
    val total = counts.values.sum() + chosen.size

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colors.surface,
        contentColor = MaterialTheme.colors.onSurface,
    ) {
        // M3 sheet, M2 content, separate LocalContentColors: M3's contentColor
        // never reaches an M2 Text, which defaults to black and vanishes on a
        // dark sheet. Hand M2 the same colour.
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colors.onSurface) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(
                    stringResource(R.string.table_pick_mine),
                    style = MaterialTheme.typography.h6,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                // Weighted, not filled: content-sized while it fits, scrolling
                // once it does not, so the buttons stay in reach.
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                    PickerRow(
                        title = stringResource(R.string.table_new_promise),
                        icon = Icons.Outlined.Edit,
                        tint = MaterialTheme.colors.primary,
                        onClick = onWrite,
                    )
                    if (templates.isNotEmpty()) {
                        PickerHeading(stringResource(R.string.table_from_presets))
                        templates.forEach { t ->
                            CountRow(
                                title = t.metadata.title.ifBlank { stringResource(R.string.untitled) },
                                count = counts[t.id] ?: 0,
                                onChange = { n -> if (n <= 0) counts.remove(t.id) else counts[t.id] = n },
                            )
                        }
                    }
                    if (held.isNotEmpty()) {
                        PickerHeading(stringResource(R.string.table_from_held))
                        held.forEach { n ->
                            PickRow(
                                title = n.metadata.title,
                                subtitle = Verifier.verify(n).stateOrNull
                                    ?.let { stringResource(R.string.from_x, Repo.nameOf(it.debtor)) },
                                picked = n.id in chosen,
                                onToggle = { if (n.id in chosen) chosen.remove(n.id) else chosen.add(n.id) },
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val ious = held.filter { it.id in chosen }
                            val mint = templates.flatMap { t -> List(counts[t.id] ?: 0) { t.metadata } }
                            onPut(ious, mint)
                        },
                    ) {
                        // Nothing chosen is a real choice: it takes my side off.
                        Text(
                            if (total == 0) stringResource(R.string.table_take_off)
                            else pluralStringResource(R.plurals.table_put_n, total, total)
                        )
                    }
                }
            }
        }
    }
}

/** A template, and how many copies of it go on the table. */
@Composable
private fun CountRow(title: String, count: Int, onChange: (Int) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 4.dp),
    ) {
        Icon(
            Icons.Outlined.ContentCopy, contentDescription = null,
            tint = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            title, style = MaterialTheme.typography.body1,
            color = MaterialTheme.colors.onSurface,
            modifier = Modifier.weight(1f).clickable { onChange(count + 1) },
        )
        IconButton(enabled = count > 0, onClick = { onChange(count - 1) }) {
            Icon(
                Icons.Default.Remove, contentDescription = stringResource(R.string.fewer),
                tint = if (count > 0) MaterialTheme.colors.primary
                else MaterialTheme.colors.onSurface.copy(alpha = 0.3f),
            )
        }
        Text(
            count.toString(),
            style = MaterialTheme.typography.body1,
            color = if (count > 0) MaterialTheme.colors.onSurface
            else MaterialTheme.colors.onSurface.copy(alpha = 0.4f),
            modifier = Modifier.width(24.dp),
            textAlign = TextAlign.Center,
        )
        IconButton(onClick = { onChange(count + 1) }) {
            Icon(
                Icons.Default.Add, contentDescription = stringResource(R.string.more),
                tint = MaterialTheme.colors.primary
            )
        }
    }
}

/** An OwU you hold: in the bundle, or not. There is only ever one of it. */
@Composable
private fun PickRow(title: String, subtitle: String?, picked: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onToggle)
            .padding(vertical = 8.dp),
    ) {
        Icon(
            if (picked) Icons.Default.CheckCircle else Icons.AutoMirrored.Filled.Send,
            contentDescription = null,
            tint = if (picked) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.body1, color = MaterialTheme.colors.onSurface)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                )
            }
        }
    }
}

/** A row that does one thing when tapped. */
@Composable
private fun PickerRow(
    title: String,
    icon: ImageVector,
    tint: Color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Text(title, style = MaterialTheme.typography.body1, color = tint)
    }
}

@Composable
private fun PickerHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.caption,
        color = MaterialTheme.colors.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

/** The table between two people: what each put down, and who said yes.
 *  Tapping my side opens the picker; the button is my yes. */
@Composable
fun TradeTable(
    table: PeerEngine.Table,
    theirName: String,
    theirKey: String?,
    enabled: Boolean,
    onPut: () -> Unit,
    onAccept: () -> Unit,
    modifier: Modifier = Modifier,
    /** My side is a promise of theirs: saying yes hands it home and closes it. */
    redeeming: Boolean = false,
    /** Ids of ious on the table whose place we are not at, to be shown in red. */
    outOfPlace: Set<String> = emptySet(),
) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.table_title),
                style = MaterialTheme.typography.subtitle2,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.weight(1f),
            )
            TextButton(enabled = enabled, onClick = onPut) {
                Text(stringResource(if (table.mine.isEmpty()) R.string.table_put else R.string.table_change))
            }
        }
        TableSide(
            who = stringResource(R.string.table_you),
            ious = table.mine,
            accepted = table.accepted,
            owner = Repo.me,
            recipient = theirKey,
            outOfPlace = outOfPlace,
            modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { onPut() },
        )
        Divider()
        TableSide(
            who = theirName,
            ious = table.theirs,
            accepted = table.theyAccepted,
            owner = theirKey,
            recipient = Repo.me,
            outOfPlace = outOfPlace,
            modifier = Modifier.fillMaxWidth(),
        )
        if (table.conflict) {
            Text(
                stringResource(R.string.table_conflict),
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Row(
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Button(enabled = enabled && !table.empty && !table.accepted && !table.conflict, onClick = onAccept) {
                Text(stringResource(if (redeeming) R.string.action_redeem else R.string.action_confirm))
            }
        }
        if (table.accepted && !table.theyAccepted) {
            Text(
                stringResource(R.string.table_waiting_for, theirName), style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun TableSide(
    who: String,
    ious: List<Iou>,
    accepted: Boolean,
    /** Who put this side down: their own promise, or one they pass on. */
    owner: String?,
    /** Who this side is going to, which is how an OwU coming home is known. */
    recipient: String?,
    outOfPlace: Set<String>,
    modifier: Modifier = Modifier,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.padding(vertical = 8.dp)) {
        Text(
            who, style = MaterialTheme.typography.body2, color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.width(72.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            if (ious.isEmpty()) {
                Text(
                    stringResource(R.string.table_nothing),
                    style = MaterialTheme.typography.body1,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                )
            }
            // Five of the same promise is one line saying five, not five lines.
            ious.groupBy { it.metadata }.forEach { (metadata, same) ->
                val first = same.first()
                Text(
                    if (same.size > 1) "${same.size} × ${metadata.title}" else metadata.title,
                    style = MaterialTheme.typography.body1,
                    color = MaterialTheme.colors.onSurface,
                )
                // Who has to make good on it: the thing worth knowing.
                Verifier.verify(first).stateOrNull?.let { state ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when (state.debtor) {
                                // Whoever put it down owes it themselves, which
                                // on my own side is me.
                                owner -> stringResource(
                                    if (owner == Repo.me) R.string.own_promise_yours else R.string.own_promise
                                )

                                Repo.me -> stringResource(R.string.own_promise_mine)
                                else -> stringResource(R.string.owed_by_x, Repo.nameOf(state.debtor))
                            },
                            style = MaterialTheme.typography.caption,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                        )
                        if (state.debtor != Repo.me) KnownMark(state.debtor)
                    }
                    // When and where: what the one who owes it is being asked
                    // about. Going home to its debtor is a redemption, so the
                    // window counts.
                    MetaGates(
                        metadata = metadata,
                        homecoming = state.debtor == recipient,
                        outOfPlace = same.any { it.id in outOfPlace },
                    )
                }
            }
        }
        if (accepted) Icon(
            Icons.Default.Check, contentDescription = stringResource(R.string.action_accept),
            tint = MaterialTheme.colors.primary
        )
    }
}

/** A QR code drawn straight onto a canvas; always black on white so any scanner reads it. */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
    }
    Canvas(modifier.background(Color.White).padding(12.dp)) {
        val cell = minOf(size.width / matrix.width, size.height / matrix.height)
        val ox = (size.width - cell * matrix.width) / 2
        val oy = (size.height - cell * matrix.height) / 2
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix[x, y]) drawRect(
                Color.Black,
                Offset(ox + x * cell, oy + y * cell),
                Size(cell + 0.5f, cell + 0.5f)
            )
        }
    }
}

/**
 * An optional instant, picked with the platform's date and time dialogs.
 * Read-only field, a tap opens the pickers, the trailing button clears it.
 */
@Composable
fun DateTimeField(
    label: String,
    value: Long?,
    onChange: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    fun pick() {
        val zone = ZoneId.systemDefault()
        val start = value?.let { Instant.ofEpochSecond(it).atZone(zone) }
            ?: ZonedDateTime.now(zone).plusHours(1).withMinute(0)
        DatePickerDialog(context, { _, y, m, d ->
            TimePickerDialog(context, { _, h, min ->
                onChange(ZonedDateTime.of(y, m + 1, d, h, min, 0, 0, zone).toEpochSecond())
            }, start.hour, start.minute, DateFormat.is24HourFormat(context)).show()
        }, start.year, start.monthValue - 1, start.dayOfMonth).show()
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            OutlinedTextField(
                value = value?.let { formatTime(it) } ?: "",
                onValueChange = {},
                readOnly = true,
                label = { Text(label) },
                modifier = Modifier.fillMaxWidth(),
            )
            // The field swallows taps; a transparent layer over it opens the pickers.
            Box(Modifier.matchParentSize().clickable { pick() })
        }
        IconButton(onClick = { onChange(null) }, enabled = value != null) {
            Icon(Icons.Default.Clear, contentDescription = null)
        }
    }
}

/**
 * The strings an OwU carries about when and where it may be redeemed, under
 * whatever it is attached to. They are never a bar - a late OwU can still be
 * handed back - so they are here to be read, by the holder deciding to ask
 * and by the one who wrote it deciding to say yes.
 */
@Composable
fun MetaGates(
    metadata: Metadata,
    /** This OwU is going home, so a window that is merely not open yet counts against it. */
    homecoming: Boolean = false,
    /** We are not where it says, as far as this phone can tell. */
    outOfPlace: Boolean = false,
) {
    if (metadata.hasWindow) {
        val gate = metadata.timeGate()
        // Expired is red wherever it is shown; too early only matters when
        // somebody is trying to redeem it now.
        val wrong = gate is TimeGate.Expired || (homecoming && gate is TimeGate.NotYet)
        GateLine(
            icon = Icons.Default.Schedule,
            text = when (gate) {
                is TimeGate.NotYet -> stringResource(R.string.window_from, formatTime(gate.opensAt))
                is TimeGate.Expired -> stringResource(R.string.window_expired)
                TimeGate.Open -> metadata.notAfter?.let { stringResource(R.string.window_until, formatTime(it)) } ?: ""
            },
            wrong = wrong,
        )
    }
    metadata.geoloc?.let { geo ->
        GateLine(
            icon = Icons.Default.Place,
            text = geo.label ?: "%.4f, %.4f".format(geo.latitude, geo.longitude),
            wrong = outOfPlace,
        )
    }
}

@Composable
private fun GateLine(icon: ImageVector, text: String, wrong: Boolean) {
    val color = if (wrong) MaterialTheme.colors.error else MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = color)
        Spacer(Modifier.width(2.dp))
        Text(text, style = MaterialTheme.typography.caption, color = color)
    }
}

/**
 * Go back, unless there is nothing to go back to. `popBackStack()` on the last
 * entry leaves the NavHost with nothing to draw - a blank screen - which is
 * what two quick taps on a back arrow, or one racing the system gesture, do.
 */
fun NavHostController.up() {
    if (previousBackStackEntry != null) popBackStack()
}

/**
 * A bundle in one line - "2 × 1 Beer, 1 Coffee" - rather than the same title
 * over and over.
 */
fun bundleLine(ious: List<Iou>): String =
    ious.groupingBy { it.metadata.title }.eachCount().entries
        .joinToString(", ") { (title, n) -> if (n > 1) "$n × $title" else title }
