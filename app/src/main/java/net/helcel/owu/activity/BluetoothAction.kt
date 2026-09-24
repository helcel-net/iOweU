package net.helcel.owu.activity

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.LocalContentColor
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import net.helcel.owu.R
import net.helcel.owu.ble.BlePermissions
import net.helcel.owu.helper.toast
import net.helcel.owu.peer.PeerManager
import net.helcel.owu.peer.PeerManager.Radio

/** Top-bar Bluetooth state. Tap: request permission, or ask the system to enable. */
@Composable
fun BluetoothAction() {
    val context = LocalContext.current
    val radio by PeerManager.radio.collectAsState()
    val onAir by PeerManager.state.collectAsState()

    // Result unused: the adapter broadcast starts us.
    val enable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        PeerManager.resume(context)
        if (granted.values.all { it } && PeerManager.radio.value == Radio.OFF) {
            enable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
    }

    val live = radio == Radio.ON && onAir.active
    val (icon, label) = when {
        live -> Icons.Default.Bluetooth to R.string.bluetooth_on
        radio == Radio.ON || radio == Radio.TURNING_ON ->
            Icons.AutoMirrored.Filled.BluetoothSearching to R.string.bluetooth_starting
        else -> Icons.Default.BluetoothDisabled to R.string.bluetooth_off
    }
    IconButton(onClick = {
        when (radio) {
            Radio.UNSUPPORTED -> context.toast(context.getString(R.string.bluetooth_unsupported))
            Radio.NO_PERMISSION -> permissions.launch(BlePermissions.required().toTypedArray())
            Radio.OFF -> runCatching { enable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                // Fallback when the enable prompt is missing.
                .onFailure { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            Radio.TURNING_ON -> Unit
            Radio.ON -> {
                if (!live) PeerManager.resume(context)
                context.toast(onAir.error ?: context.getString(label))
            }
        }
    }) {
        Icon(
            icon,
            contentDescription = stringResource(label),
            tint = LocalContentColor.current.copy(alpha = if (live) 1f else 0.6f),
        )
    }
}
