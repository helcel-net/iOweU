package net.helcel.owu.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import net.helcel.owu.R
import net.helcel.owu.ledger.Iou
import net.helcel.owu.ledger.IouState
import net.helcel.owu.ledger.Status
import net.helcel.owu.ledger.Verifier
import net.helcel.owu.store.Repo

/**
 * OwUs that are over. They are kept - a spent promise is the proof it was
 * kept - but they are history, so they live here rather than among the OwUs
 * that still mean something.
 */
@Composable
fun RedeemedScreen(nav: NavHostController) = IouListPage(
    nav = nav,
    title = stringResource(R.string.redeemed_title),
    empty = stringResource(R.string.redeemed_empty),
) { _, state -> isSpent(state) }

/**
 * What you owe: promises of yours that somebody else is holding. They are
 * somebody else's to bring back, so they are not among the OwUs you can act
 * on - but you should be able to look them in the eye.
 */
@Composable
fun OwedScreen(nav: NavHostController) = IouListPage(
    nav = nav,
    title = stringResource(R.string.owed_title),
    empty = stringResource(R.string.owed_empty),
) { _, state -> owedByMe(state) }

/** A promise of mine that somebody else is holding. */
internal fun owedByMe(state: IouState): Boolean =
    state.status != Status.REDEEMED && state.debtor == Repo.me && state.holder != Repo.me

/** A promise that has been kept, and is now only a record. */
internal fun isSpent(state: IouState): Boolean = state.status == Status.REDEEMED

/** A screen that is one filtered list of OwUs and nothing else. */
@Composable
private fun IouListPage(
    nav: NavHostController,
    title: String,
    empty: String,
    include: (Iou, IouState) -> Boolean,
) {
    val ious by Repo.store.ious.collectAsState()
    val rows = remember(ious, title) {
        ious.values.mapNotNull { iou -> Verifier.verify(iou).stateOrNull?.let { iou to it } }
            .filter { include(it.first, it.second) }
            .sortedByDescending { it.first.head.timestamp }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = { nav.up() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .background(MaterialTheme.colors.background)
        ) {
            if (rows.isEmpty()) {
                Text(
                    empty,
                    style = MaterialTheme.typography.body1,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
            }
            LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                items(rows.size) { i ->
                    val (iou, state) = rows[i]
                    IouRow(iou, state) { nav.navigate("iou/${iou.id}") }
                }
            }
        }
    }
}
