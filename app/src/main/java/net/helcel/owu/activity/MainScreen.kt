package net.helcel.owu.activity

import android.os.Bundle
import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material.AppBarDefaults
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.LocalElevationOverlay
import androidx.compose.material.MaterialTheme
import androidx.compose.material.primarySurface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import net.helcel.owu.ble.BlePermissions
import net.helcel.owu.peer.PeerManager
import net.helcel.owu.store.Repo


class MainScreen : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        // Draw behind the system bars: no colour named in a file can match a
        // Material You bar chosen from the wallpaper. Both fully transparent,
        // since the default scrims the navigation bar a shade darker and looks
        // like a second mismatch; [SystemBars] gives the icons their contrast.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )

        setContent {
            SysTheme {
                // What a Material 2 app bar paints itself: primarySurface
                // *plus* the dark theme's elevation overlay, which lifts a 4dp
                // bar off the background. Miss the overlay and the strip above
                // it is a near miss, which reads worse than a plain difference.
                val bar = MaterialTheme.colors.primarySurface
                val barPainted = LocalElevationOverlay.current
                    ?.apply(bar, AppBarDefaults.TopAppBarElevation) ?: bar
                SystemBars(status = barPainted, navigation = MaterialTheme.colors.background)
                Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colors.background)) {
                    // Status bar in the top bar's colour, the rest in the
                    // content's. Inside both insets, or the system buttons sit
                    // on whatever is at the foot of a screen.
                    Spacer(
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .windowInsetsTopHeight(WindowInsets.statusBars)
                            .background(barPainted)
                    )
                    Box(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                        Root()
                    }
                }
            }
        }
    }

    // BLE only while resumed. Rotation is exempt so trades survive it.
    override fun onPause() {
        super.onPause()
        if (!isChangingConfigurations) PeerManager.pause()
    }

    /** Bar icons legible against what is behind them. The colour is a Material
     *  You one chosen on the device, so it is measured, not assumed. */
    @Composable
    private fun SystemBars(status: Color, navigation: Color) {
        val view = LocalView.current
        val paleStatus = status.luminance() > 0.5f
        val paleNavigation = navigation.luminance() > 0.5f
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = paleStatus
                isAppearanceLightNavigationBars = paleNavigation
            }
        }
    }

    /** Opens the store and the Keystore key off the main thread, then shows the app. */
    @Composable
    fun Root() {
        val context = LocalContext.current
        val ready by Repo.ready.collectAsState()
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) { Repo.init(context.applicationContext) }
        }
        if (!ready) {
            Box(
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colors.background),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colors.primary,
                    strokeWidth = 4.dp,
                    modifier = Modifier.size(50.dp),
                )
            }
        } else {
            // BLE on while the app is in use.
            val permissions = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { granted -> if (granted.values.all { it }) PeerManager.resume(context) }
            var askedFor by rememberSaveable { mutableStateOf(false) }
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            DisposableEffect(lifecycle) {
                // Runs only once the store has opened, which on a cold start is
                // after ON_RESUME has gone by, and an observer hears only what
                // comes next. So ask where the lifecycle is, not just where it goes.
                fun begin() {
                    // Always: keeps the icon current without permission.
                    PeerManager.resume(context)
                    if (!BlePermissions.granted(context) && !askedFor) {
                        askedFor = true
                        permissions.launch(BlePermissions.required().toTypedArray())
                    }
                }
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) begin()
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) begin()
                }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }
            // Hoisted out of the nav host: one of the prompts below navigates.
            val nav = rememberNavController()
            AppNavHost(nav)
            // Being asked to pay is worth interrupting for, wherever you are.
            RedeemPrompt()
            // Somebody opening a table with you is worth interrupting for too.
            TradePrompt(nav)
            PendingAsk()
            // And what came of it: a table can finish with neither side looking.
            OutcomeBanner()
        }
    }

    @Composable
    fun AppNavHost(nav: NavHostController) {
        NavHost(nav, startDestination = "main") {
            composable("main") { IouListScreen(nav) }
            // Two ways to write a promise: once, or as a template to reuse.
            composable("issue") { IssueScreen(nav) }
            composable("template") { IssueScreen(nav, asTemplate = true) }
            composable("template/{id}") {
                IssueScreen(nav, templateId = it.arguments?.getString("id"), asTemplate = true)
            }
            composable("iou/{id}") { IouDetailScreen(nav, it.arguments?.getString("id") ?: "") }
            composable("identity") { IdentityScreen(nav) }
            composable("peer/{beacon}") { PeerScreen(nav, it.arguments?.getString("beacon") ?: "") }
            composable("settings") {
                SettingsMainScreen(
                    onExit = { nav.up() },
                    onRedeemed = { nav.navigate("redeemed") },
                    onOwed = { nav.navigate("owed") },
                    onTemplates = { nav.navigate("templates") },
                )
            }
            composable("redeemed") { RedeemedScreen(nav) }
            composable("owed") { OwedScreen(nav) }
            composable("templates") { TemplatesScreen(nav) }
        }
    }
}
