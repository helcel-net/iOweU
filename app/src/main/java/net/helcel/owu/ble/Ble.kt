package net.helcel.owu.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import net.helcel.owu.crypto.Hash
import java.util.Base64

object Ble {
    /**
     * Manufacturer id our packets ride under. 0xFFFF is the id the SIG
     * reserves for internal use and testing; our service UUID is what keeps
     * other testers' packets out.
     */
    const val MANUFACTURER = 0xFFFF

    /** Eight bytes of a key's SHA-256: what a device is known as on the air. */
    fun beacon(publicKey: String): ByteArray =
        Hash.sha256(Base64.getDecoder().decode(publicKey)).copyOf(8)

    fun beaconHex(publicKey: String): String = hex(beacon(publicKey))

    /** A beacon as text, for keying maps by device. */
    fun hex(beacon: ByteArray): String = Hash.hex(beacon)
}

object BlePermissions {
    /** Runtime permissions Bluetooth needs on this OS version. */
    fun required(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) else listOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun granted(context: Context): Boolean = required().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun adapter(context: Context): BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter
}

/** What the link hands up: somebody is there, or somebody said something. */
sealed class Heard {
    /** A device's advertisement, and how strongly it came in. */
    class Presence(val beacon: ByteArray, val rssi: Int) : Heard()

    /** One whole message, and the beacon (or its leading bytes) it came from. */
    class Message(val from: ByteArray, val bytes: ByteArray) : Heard()
}
