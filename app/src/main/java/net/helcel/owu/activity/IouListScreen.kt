package net.helcel.owu.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.Card
import androidx.compose.material.FloatingActionButton
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import net.helcel.owu.BuildConfig
import net.helcel.owu.R
import net.helcel.owu.ledger.Block
import net.helcel.owu.ledger.Iou
import net.helcel.owu.ledger.IouState
import net.helcel.owu.ledger.Status
import net.helcel.owu.ledger.Verifier
import net.helcel.owu.store.Template
import net.helcel.owu.store.Repo

@Composable
fun IouListScreen(nav: NavHostController) {
    val ious by Repo.store.ious.collectAsState()
    // Subscribed, never read: names come from contacts through Repo.nameOf(),
    // which is a plain lookup, so without this nothing here would notice a
    // contact being added or renamed.
    @Suppress("UNUSED_VARIABLE") val contacts by Repo.store.contacts.collectAsState()

    val me = Repo.me
    val verified = remember(ious) {
        ious.values.mapNotNull { iou -> Verifier.verify(iou).stateOrNull?.let { iou to it } }
            .sortedByDescending { it.first.head.timestamp }
    }
    // OwUs that are over live under Settings; this screen is what is live.
    // Anything in your hands is held - including a promise of your own that
    // is on its way somewhere, which says "not given yet" for itself. One
    // somebody else holds and you wrote is a debt.
    val open = verified.filterNot { it.second.status == Status.REDEEMED }
    val held = open.filter { it.second.holder == me }
    // OwUs you can do nothing about from here - what you owe, what is spent,
    // and the promises you keep ready - are all under Settings.
    val other = open.filterNot { it.second.holder == me || it.second.debtor == me }

    val titleOther = stringResource(R.string.section_other)

    Scaffold(
        topBar = {
            TopAppBar(
                // The outward name, the same one on the launcher: this bar is
                // the first thing anybody sees of the app.
                title = { Text(BuildConfig.PUBLIC_NAME) },
                actions = {
                    // No trade icon here any more. Trading is something you do
                    // with a person, not with the app, so it starts from the
                    // person - tap them under your own profile and their table
                    // opens. One fewer thing in the bar, and one fewer screen
                    // between wanting to trade and trading.
                    BluetoothAction()
                    IconButton(onClick = { nav.navigate("identity") }) {
                        Icon(Icons.Default.Person, contentDescription = stringResource(R.string.identity_title))
                    }
                    IconButton(onClick = { nav.navigate("settings") }) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.action_settings))
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { nav.navigate("issue") }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.action_new))
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .background(MaterialTheme.colors.background)
        ) {
            if (open.isEmpty()) {
                Text(
                    stringResource(R.string.list_empty),
                    style = MaterialTheme.typography.body1,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
            }
            LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                // What you hold needs no heading: it is what this screen is.
                section(null, held, nav)
                section(titleOther, other, nav)
                item { Spacer(Modifier.size(80.dp)) } // room under the FAB
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String?,
    rows: List<Pair<Iou, IouState>>,
    nav: NavHostController,
) {
    if (rows.isEmpty()) return
    if (title != null) item { SectionHeader(title) }
    items(rows.size) { i ->
        val (iou, state) = rows[i]
        IouRow(iou, state) { nav.navigate("iou/${iou.id}") }
    }
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.subtitle2,
        color = MaterialTheme.colors.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

/**
 * "from Alice" when you hold it, "to Bob" when you owe it, and for an OwU
 * sitting with the person who made it, what that means: not given yet, back
 * with them, or spent.
 */
@Composable
fun counterpartyLine(state: IouState): String = when {
    state.debtor == state.holder -> when {
        state.status == Status.REDEEMED -> stringResource(R.string.closed_by_x, Repo.nameOf(state.debtor))
        state.debtor == Repo.me -> stringResource(R.string.yours_to_give)
        else -> stringResource(R.string.back_with_x, Repo.nameOf(state.debtor))
    }

    state.debtor == Repo.me -> stringResource(R.string.to_x, Repo.nameOf(state.holder))
    state.holder == Repo.me -> stringResource(R.string.from_x, Repo.nameOf(state.debtor))
    else -> "${Repo.nameOf(state.debtor)} → ${Repo.nameOf(state.holder)}"
}

@Composable
fun IouRow(iou: Iou, state: IouState, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        elevation = 2.dp,
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(iou.metadata.title, style = MaterialTheme.typography.subtitle1, fontWeight = FontWeight.Bold)
                Text(
                    counterpartyLine(state),
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                )
                MetaGates(iou.metadata)
            }
            // No status here: every row on a list is the same status as the
            // list it is on, so the label only ever repeated the screen.
        }
    }
}

@Composable
fun TemplateRow(template: Template, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        elevation = 2.dp,
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    template.metadata.title.ifBlank { stringResource(R.string.untitled) },
                    style = MaterialTheme.typography.subtitle1, fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(R.string.template_ready),
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                )
            }
            // How many are out there, so a preset shows what it has done.
            val given = Repo.store.ious.collectAsState().value.values.count {
                it.metadata == template.metadata && (it.chain.firstOrNull() as? Block.Issue)?.debtor == Repo.me && it.chain.size > 1
            }
            if (given > 0) {
                Text(
                    pluralStringResource(R.plurals.template_given, given, given),
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                )
            }
        }
    }
}

