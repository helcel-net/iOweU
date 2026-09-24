package net.helcel.owu.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import net.helcel.owu.R
import net.helcel.owu.activity.sub.AboutScreen
import net.helcel.owu.ledger.Verifier
import net.helcel.owu.store.Repo


@Preview
@Composable
fun SettingsMainScreen(
    onExit: () -> Unit = {},
    onRedeemed: () -> Unit = {},
    onOwed: () -> Unit = {},
    onTemplates: () -> Unit = {},
) {
    val nav: NavHostController = rememberNavController()
    SysTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.action_settings)) },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (!nav.popBackStack())
                                onExit()
                        }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = null
                            )
                        }
                    }
                )
            }
        ) { innerPadding ->
            // One host, inside the scaffold. Building it here and again as a
            // default argument left two of them: settings drawn twice, and
            // only the inner one ever navigating.
            Box(modifier = Modifier.padding(innerPadding)) {
                NavHost(nav, startDestination = "settings") {
                    composable("settings") { SettingsScreen(nav, onRedeemed, onOwed, onTemplates) }
                    composable("about") { AboutScreen() }
                }
            }
        }
    }
}

/**
 * Four kinds of thing in one screen - lists to open, a switch, a choice, and
 * two actions - so each kind gets one shape and keeps it: rows that open
 * something look like rows, and only the backup buttons look like buttons.
 */
@Composable
fun SettingsScreen(
    navController: NavHostController,
    onRedeemed: () -> Unit = {},
    onOwed: () -> Unit = {},
    onTemplates: () -> Unit = {},
) {
    val context = LocalContext.current
    // What each list holds, counted the same way the list itself filters.
    val ious by Repo.store.ious.collectAsState()
    val templates by Repo.store.templates.collectAsState()
    val (owed, spent) = remember(ious) {
        val states = ious.values.mapNotNull { Verifier.verify(it).stateOrNull }
        states.count(::owedByMe) to states.count(::isSpent)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp)
    ) {
        // OwUs you cannot act on from the home screen - what you keep ready,
        // what you owe, and what is spent - are worth looking up.
        Section(stringResource(R.string.pref_category_notes)) {
            NavRow(stringResource(R.string.section_templates), templates.size, onTemplates)
            NavRow(stringResource(R.string.owed_title), owed, onOwed)
            NavRow(stringResource(R.string.redeemed_title), spent, onRedeemed)
        }

        Section(stringResource(R.string.key_theme)) {
            Segmented(
                listOf(
                    stringResource(R.string.system),
                    stringResource(R.string.light),
                    stringResource(R.string.dark),
                ),
                ThemeChoice.current(context),
            ) { picked -> ThemeChoice.set(context, picked) }
        }

        Section(stringResource(R.string.pref_category_data)) {
            BackupSection()
        }

        Spacer(Modifier.height(16.dp))
        PreferenceButton(stringResource(R.string.about)) {
            if (navController.currentDestination?.route != "about")
                navController.navigate("about")
        }
    }
}

/** A heading in the app's own section style, and whatever belongs under it. */
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    SectionHeader(title)
    content()
}

/** A row that opens a list: what it is, how much is in it, and a chevron. */
@Composable
private fun NavRow(title: String, count: Int, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.body1,
            color = MaterialTheme.colors.onBackground,
            modifier = Modifier.weight(1f),
        )
        // An empty list says so when you open it; no need for a nought here.
        if (count > 0) {
            Text(
                count.toString(),
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colors.onSurface.copy(alpha = 0.4f),
            modifier = Modifier.padding(start = 8.dp).size(20.dp),
        )
    }
}

/** One of a few, side by side: shorter than a stack of radio buttons. */
@Composable
private fun Segmented(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(shape)
            .border(1.dp, MaterialTheme.colors.primary.copy(alpha = 0.4f), shape)
    ) {
        options.forEach { option ->
            val on = option == selected
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .background(if (on) MaterialTheme.colors.primary else Color.Transparent)
                    .clickable { onSelect(option) },
            ) {
                Text(
                    option,
                    style = MaterialTheme.typography.button,
                    color = if (on) MaterialTheme.colors.onPrimary else MaterialTheme.colors.onBackground,
                )
            }
        }
    }
}

/** The one thing here that is not a setting, and so is not a row. */
@Composable
fun PreferenceButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) { Text(text) }
}
