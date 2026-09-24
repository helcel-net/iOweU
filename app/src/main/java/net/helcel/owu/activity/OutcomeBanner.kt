package net.helcel.owu.activity

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.Card
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.helcel.owu.R
import net.helcel.owu.peer.Outcome
import net.helcel.owu.peer.PeerMessage
import net.helcel.owu.peer.PeerManager
import net.helcel.owu.store.Repo

/**
 * What just happened, said out loud. A table used to finish by quietly
 * emptying itself, and a redeem answered from a prompt finished with no screen
 * at all. This lives at the app root and speaks wherever you are.
 */
@Composable
fun OutcomeBanner() {
    // Two states, not one: the card keeps saying what it said while it slides
    // away, so the words outlive the showing.
    var outcome by remember { mutableStateOf<Outcome?>(null) }
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        PeerManager.outcomes.collect {
            outcome = it
            visible = true
        }
    }
    LaunchedEffect(outcome) {
        if (outcome == null) return@LaunchedEffect
        delay(SHOW_MS)
        visible = false
    }

    Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            outcome?.let { Banner(it) { visible = false } }
        }
    }
}

@Composable
private fun Banner(outcome: Outcome, onDismiss: () -> Unit) {
    // A refusal is no failure, and must not look like a success either.
    val no = outcome.declined != null
    val paper = if (no) MaterialTheme.colors.surface else MaterialTheme.colors.primary
    val ink = if (no) MaterialTheme.colors.onSurface else MaterialTheme.colors.onPrimary
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onDismiss),
        elevation = 8.dp,
        backgroundColor = paper,
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (no) Icons.Default.Close else Icons.Default.Check,
                contentDescription = null,
                tint = paper,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(ink.copy(alpha = if (no) 0.5f else 1f))
                    .padding(4.dp),
            )
            Column(modifier = Modifier.padding(start = 16.dp)) {
                Text(
                    headline(outcome),
                    style = MaterialTheme.typography.body1,
                    fontWeight = FontWeight.Medium,
                    color = ink,
                )
                outcome.peer?.let {
                    Text(
                        stringResource(if (no) R.string.from_x else R.string.done_with, Repo.nameOf(it)),
                        style = MaterialTheme.typography.caption,
                        color = ink.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

/** The one sentence for it: redeeming first, since that is what ends a loop. */
@Composable
private fun headline(outcome: Outcome): String = when {
    outcome.declined == PeerMessage.Asked.INVITE -> stringResource(R.string.done_declined_trade)
    outcome.declined != null -> stringResource(R.string.done_declined_ask)
    outcome.redeemed.isNotEmpty() -> stringResource(R.string.done_redeemed, bundleLine(outcome.redeemed))
    outcome.gave.isNotEmpty() && outcome.got.isNotEmpty() ->
        stringResource(R.string.done_traded, bundleLine(outcome.gave), bundleLine(outcome.got))

    outcome.gave.isNotEmpty() -> stringResource(R.string.done_gave, bundleLine(outcome.gave))
    else -> stringResource(R.string.done_got, bundleLine(outcome.got))
}

/** Long enough to read, short enough not to be in the way. */
private const val SHOW_MS = 4_000L
