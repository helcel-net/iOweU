package net.helcel.owu.activity

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Scaffold
import androidx.compose.material.Switch
import androidx.compose.material.Text
import androidx.compose.material.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import net.helcel.owu.R
import net.helcel.owu.peer.PeerManager
import net.helcel.owu.helper.Locator
import net.helcel.owu.helper.toast
import net.helcel.owu.ledger.GeoLoc
import net.helcel.owu.ledger.Ledger
import net.helcel.owu.ledger.Metadata
import net.helcel.owu.store.Template
import net.helcel.owu.store.Repo
import java.util.UUID

/**
 * Write a promise. Two jobs, differing in what is left behind.
 *
 * A **template** ([asTemplate]) is kept as written and signs nothing; an OwU is
 * minted from it each time it goes on a table. [templateId] is null for a new
 * one, else the one being edited.
 *
 * A **one-off** is signed there and then and nothing is filed: into your own
 * hands from the home screen, onto the table if written at one.
 */
@Composable
fun IssueScreen(nav: NavHostController, templateId: String? = null, asTemplate: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val template = remember(templateId) { templateId?.let { Repo.store.templates.value[it] } }
    val id = remember { template?.id ?: UUID.randomUUID().toString() }

    var title by remember { mutableStateOf(template?.metadata?.title ?: "") }
    var description by remember { mutableStateOf(template?.metadata?.description ?: "") }
    val geo = template?.metadata?.geoloc
    var hasGeo by remember { mutableStateOf(geo != null) }
    var geoLabel by remember { mutableStateOf(geo?.label ?: "") }
    var lat by remember { mutableStateOf(geo?.latitude?.toString() ?: "") }
    var lon by remember { mutableStateOf(geo?.longitude?.toString() ?: "") }
    var radius by remember { mutableStateOf(geo?.radiusM?.toString() ?: "200") }
    var locating by remember { mutableStateOf(false) }
    // Backing out of a trade's new promise leaves nothing pending.
    DisposableEffect(Unit) { onDispose { TradeIntent.pending = null } }
    var notBefore by remember { mutableStateOf(template?.metadata?.notBefore) }
    var notAfter by remember { mutableStateOf(template?.metadata?.notAfter) }
    var hasWindow by remember { mutableStateOf(template?.metadata?.hasWindow == true) }
    var nonTransferable by remember { mutableStateOf(template?.metadata?.nonTransferable == true) }

    // The keyboard walks through the form: every field offers "next" and
    // moves focus on, and the last one offers "done" and puts the keyboard
    // away. Next, not Down: latitude and longitude sit side by side, and
    // "down" from latitude skipped past longitude to the radius below.
    val focus = LocalFocusManager.current
    val onwards = KeyboardActions(
        onNext = { focus.moveFocus(FocusDirection.Next) },
        onDone = { focus.clearFocus() },
    )

    fun stepping(last: Boolean, type: KeyboardType = KeyboardType.Text) = KeyboardOptions(
        keyboardType = type,
        imeAction = if (last) ImeAction.Done else ImeAction.Next,
    )

    fun parsedGeo(): GeoLoc? {
        if (!hasGeo) return null
        val la = lat.replace(',', '.').toDoubleOrNull() ?: return null
        val lo = lon.replace(',', '.').toDoubleOrNull() ?: return null
        val r = radius.toIntOrNull() ?: return null
        if (la !in -90.0..90.0 || lo !in -180.0..180.0 || r <= 0) return null
        return GeoLoc.of(la, lo, r, geoLabel.ifBlank { null })
    }

    fun metadata() = Metadata(
        title = title.trim(),
        description = description.trim().ifBlank { null },
        geoloc = parsedGeo(),
        notBefore = notBefore.takeIf { hasWindow },
        notAfter = notAfter.takeIf { hasWindow },
        nonTransferable = nonTransferable,
    )

    fun save() {
        if (title.isBlank()) {
            context.toast(context.getString(R.string.issue_need_title)); return
        }
        if (hasGeo && parsedGeo() == null) {
            context.toast(context.getString(R.string.issue_bad_geo)); return
        }
        if (hasWindow && notBefore != null && notAfter != null && notBefore!! > notAfter!!) {
            context.toast(context.getString(R.string.issue_bad_window)); return
        }
        val saved = metadata()
        if (asTemplate) {
            Repo.store.putTemplate(Template(id, saved, template?.createdAt ?: Ledger.now()))
        } else {
            // A one-off is signed now. At a table it is minted straight onto
            // it; otherwise it is minted here, into my own hands, and shows on
            // the home screen as mine to give.
            val trading = TradeIntent.take()
            if (trading != null) PeerManager.readySession(trading.peerKey)?.putNew(saved)
            else Repo.store.put(Ledger.issue(Repo.signer, saved))
        }
        nav.up()
    }

    fun locate() {
        locating = true
        scope.launch {
            val loc = Locator.current(context)
            locating = false
            if (loc == null) {
                context.toast(context.getString(R.string.geo_unavailable))
            } else {
                lat = "%.6f".format(loc.latitude)
                lon = "%.6f".format(loc.longitude)
            }
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) locate() else context.toast(context.getString(R.string.geo_permission_denied))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                // The title says which of the two this is: a promise being
                // made once, or one being kept for next time.
                title = {
                    Text(
                        stringResource(
                            when {
                                !asTemplate -> R.string.action_new
                                template == null -> R.string.template_new
                                else -> R.string.edit_template
                            }
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { nav.up() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    if (template != null) {
                        IconButton(onClick = { Repo.store.removeTemplate(template.id); nav.up() }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
                        }
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
            OutlinedTextField(
                value = title, onValueChange = { title = it },
                label = { Text(stringResource(R.string.field_title)) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = stepping(last = false), keyboardActions = onwards,
            )
            // Room for the terms, or the occasion, or the joke. Signed with
            // the rest, so neither side can rewrite it afterwards.
            OutlinedTextField(
                value = description, onValueChange = { description = it },
                label = { Text(stringResource(R.string.field_description)) },
                placeholder = { Text(stringResource(R.string.field_description_hint)) },
                minLines = 2, maxLines = 4,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                keyboardOptions = stepping(last = false), keyboardActions = onwards,
            )
            // Only worth saying when it is going somewhere the moment it is written.
            TradeIntent.pending?.let {
                Text(
                    stringResource(R.string.issue_onto_table, Repo.nameOf(it.peerKey)),
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).clickable { hasGeo = !hasGeo },
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.field_geoloc), style = MaterialTheme.typography.subtitle1)
                    Text(
                        stringResource(R.string.field_geoloc_desc),
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                    )
                }
                Switch(checked = hasGeo, onCheckedChange = { hasGeo = it })
            }
            if (hasGeo) {
                OutlinedTextField(
                    value = geoLabel, onValueChange = { geoLabel = it },
                    label = { Text(stringResource(R.string.field_geo_label)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    keyboardOptions = stepping(last = false), keyboardActions = onwards,
                )
                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    OutlinedTextField(
                        value = lat,
                        onValueChange = { lat = it },
                        label = { Text(stringResource(R.string.field_lat)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = stepping(last = false, type = KeyboardType.Decimal),
                        keyboardActions = onwards,
                    )
                    Spacer(Modifier.size(8.dp))
                    OutlinedTextField(
                        value = lon,
                        onValueChange = { lon = it },
                        label = { Text(stringResource(R.string.field_lon)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = stepping(last = false, type = KeyboardType.Decimal),
                        keyboardActions = onwards,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = radius, onValueChange = { radius = it },
                        label = { Text(stringResource(R.string.field_radius)) },
                        singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = stepping(last = true, type = KeyboardType.Number), keyboardActions = onwards,
                    )
                    Spacer(Modifier.size(8.dp))
                    OutlinedButton(
                        enabled = !locating,
                        onClick = { if (Locator.hasPermission(context)) locate() else permission.launch(Locator.PERMISSION) },
                    ) {
                        Text(stringResource(if (locating) R.string.geo_locating else R.string.geo_use_current))
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).clickable { hasWindow = !hasWindow },
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.field_window), style = MaterialTheme.typography.subtitle1)
                    Text(
                        stringResource(R.string.field_window_desc),
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                    )
                }
                Switch(checked = hasWindow, onCheckedChange = { hasWindow = it })
            }
            if (hasWindow) {
                DateTimeField(
                    label = stringResource(R.string.field_not_before),
                    value = notBefore, onChange = { notBefore = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                DateTimeField(
                    label = stringResource(R.string.field_not_after),
                    value = notAfter, onChange = { notAfter = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).clickable { nonTransferable = !nonTransferable },
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.field_transfer), style = MaterialTheme.typography.subtitle1)
                    Text(
                        stringResource(R.string.field_transfer_desc),
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                    )
                }
                Switch(checked = nonTransferable, onCheckedChange = { nonTransferable = it })
            }

            Button(onClick = { save() }, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                Text(stringResource(if (TradeIntent.pending != null) R.string.action_save_and_offer else R.string.action_save))
            }
        }
    }
}

