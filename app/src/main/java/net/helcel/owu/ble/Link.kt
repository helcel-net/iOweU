package net.helcel.owu.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * An ordinary BLE connection. Every device advertises a connectable packet and
 * scans for the same; a connection opens only when there is something to say.
 * [Frames] framing, MTU-sized: the dialler writes the characteristic, the
 * answerer notifies on it, and both run a server so either can dial.
 *
 * A second carrier once put messages into Bluetooth 5 extended advertisements.
 * Dropped: two phones both reporting extended support could not hear each other.
 */
@SuppressLint("MissingPermission") // the manager checks BlePermissions before start()
class Link(
    /** The application context: a GATT server outlives any one screen. */
    private val context: Context,
    private val adapter: BluetoothAdapter,
    private val me: ByteArray,
    /** Advertise/scan failed after start(). Binder thread. */
    private val onBroken: (String) -> Unit = {},
) {
    private val _presence = MutableSharedFlow<Heard.Presence>(extraBufferCapacity = 64)

    /** Devices heard advertising, and what they can do. */
    val presence: SharedFlow<Heard.Presence> = _presence
    private val _messages = MutableSharedFlow<Heard.Message>(extraBufferCapacity = 64)
    val messages: SharedFlow<Heard.Message> = _messages

    /** One open connection, either one we made or one we accepted. */
    private inner class Channel(
        /** Empty until their first frame names them, on a connection we accepted. */
        var beacon: ByteArray,
        val device: BluetoothDevice,
        /** Set when we dialled; null when they did. */
        val gatt: BluetoothGatt?,
    ) {
        val frames = Frames()
        val write = Mutex()
        var mtu: Int = DEFAULT_MTU

        /** Completes when the connection is usable both ways. */
        val ready = CompletableDeferred<Boolean>()

        /** Completes when the last write was acknowledged. */
        var sent: CompletableDeferred<Boolean>? = null
    }

    private val channels = ConcurrentHashMap<String, Channel>()   // by device address
    private val addresses = ConcurrentHashMap<String, String>()   // beacon hex -> device address
    private var server: BluetoothGattServer? = null
    private var characteristic: BluetoothGattCharacteristic? = null
    private var advertiser: android.bluetooth.le.BluetoothLeAdvertiser? = null
    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null

    fun start(): String? {
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return "No Bluetooth service"
        val chr = BluetoothGattCharacteristic(
            DATA,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    CCCD,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                )
            )
        }
        val service = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            addCharacteristic(chr)
        }
        val gattServer = manager.openGattServer(context, serverCallback) ?: return "Cannot open a GATT server"
        gattServer.addService(service)
        server = gattServer
        characteristic = chr

        val adv = adapter.bluetoothLeAdvertiser ?: return "This device cannot advertise"
        advertiser = adv
        // Connectable, legacy, 31 bytes: the service to be found by and our
        // beacon to be known by.
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(SERVICE))
            .addManufacturerData(Ble.MANUFACTURER, me)
            .build()
        runCatching { adv.startAdvertising(settings, data, advertiseCallback) }
            .onFailure { return "Could not advertise: ${it.message}" }

        val scan = adapter.bluetoothLeScanner ?: return "This device cannot scan"
        scanner = scan
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
        val scanSettings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scan.startScan(listOf(filter), scanSettings, scanCallback) }
            .onFailure { return "Could not scan: ${it.message}" }
        return null
    }

    fun stop() {
        runCatching { scanner?.stopScan(scanCallback) }
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        channels.values.toList().forEach { drop(it) }
        runCatching { server?.close() }
        server = null
        advertiser = null
        scanner = null
        addresses.clear()
    }

    /**
     * Opens a connection to [beacon], or returns true if one is already up.
     * Only the side that calls this dials; the other simply accepts.
     */
    suspend fun connect(beacon: ByteArray): Boolean {
        channels.values.firstOrNull { it.beacon.contentEquals(beacon) }?.let { open ->
            return withTimeoutOrNull(CONNECT_MS) { open.ready.await() } ?: false
        }
        val address = addresses[Ble.hex(beacon)] ?: return false
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return false
        val gatt = device.connectGatt(context, false, clientCallback, BluetoothDevice.TRANSPORT_LE) ?: return false
        val channel = Channel(beacon, device, gatt)
        channels[address] = channel
        val ok = withTimeoutOrNull(CONNECT_MS) { channel.ready.await() } ?: false
        if (!ok) drop(channel)
        return ok
    }

    /** Hangs up on [beacon]; harmless if there is nothing to hang up on. */
    fun disconnect(beacon: ByteArray) {
        channels.values.filter { it.beacon.contentEquals(beacon) }.forEach { drop(it) }
    }

    private fun drop(channel: Channel) {
        channels.remove(channel.device.address)
        channel.frames.clear()
        if (!channel.ready.isCompleted) channel.ready.complete(false)
        channel.sent?.takeIf { !it.isCompleted }?.complete(false)
        if (channel.gatt != null) {
            runCatching { channel.gatt.disconnect() }
            runCatching { channel.gatt.close() }
        } else {
            runCatching { server?.cancelConnection(channel.device) }
        }
    }

    /** Sends one message to [to]; true once the link took it. With [dial],
     *  connects first if there is none - what a person's action deserves and
     *  a HELLO said into the dark does not. */
    suspend fun send(to: ByteArray, message: ByteArray, dial: Boolean = false): Boolean {
        val channel = channelTo(to) ?: (if (dial && connect(to)) channelTo(to) else null)
        if (channel == null) {
            Log.w(
                TAG,
                "send ${message.size}B to ${Ble.hex(to)}: no channel, have ${channels.values.map { Ble.hex(it.beacon) }}"
            )
            return false
        }
        if (!channel.ready.isCompleted && withTimeoutOrNull(CONNECT_MS) { channel.ready.await() } != true) {
            Log.w(TAG, "send ${message.size}B to ${Ble.hex(to)}: channel never became ready")
            drop(channel)
            return false
        }
        val frame = channel.frames.frame(me, message)
        // One write is an attribute value, which is 512 bytes however large
        // the MTU says a packet may be.
        val room = minOf(channel.mtu - ATT_OVERHEAD, MAX_ATTRIBUTE)
        val kind = if (channel.gatt != null) "write" else "notify"
        return channel.write.withLock {
            var n = 0
            for (chunk in channel.frames.chunks(frame, room)) {
                n++
                if (!writeChunk(channel, chunk)) {
                    // A refused or unacknowledged write means this connection
                    // is finished, whatever the stack thinks. Left in place it
                    // is found by every later attempt, believed ready, and
                    // fails the same way for ever. Dropped, the next one dials.
                    Log.w(
                        TAG,
                        "send ${message.size}B to ${Ble.hex(to)}: $kind chunk $n of ${chunk.size}B failed, mtu ${channel.mtu}"
                    )
                    drop(channel)
                    return@withLock false
                }
            }
            true
        }
    }

    /**
     * The connection to [to], if we have one. One we accepted is nameless
     * until their first frame, and must *not* be guessed at: guessing wrong
     * hands somebody else's promise to whoever is connected. Dialling our own
     * costs a second and is never wrong.
     */
    private fun channelTo(to: ByteArray): Channel? =
        channels.values.firstOrNull { it.beacon.contentEquals(to) }

    private suspend fun writeChunk(channel: Channel, chunk: ByteArray): Boolean {
        val done = CompletableDeferred<Boolean>()
        channel.sent = done
        val chr = characteristic ?: return false
        val ok = runCatching { putOnLink(channel, chr, chunk) }.getOrElse {
            Log.w(TAG, "write refused", it)
            false
        }
        if (!ok) return false
        return withTimeoutOrNull(WRITE_MS) { done.await() } ?: false
    }

    /** The one write, whichever end of the connection we are. */
    private fun putOnLink(channel: Channel, chr: BluetoothGattCharacteristic, chunk: ByteArray): Boolean {
        return if (channel.gatt != null) {
            val client = channel.gatt.getService(SERVICE)?.getCharacteristic(DATA) ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                channel.gatt.writeCharacteristic(client, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                        BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    client.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    client.value = chunk
                    channel.gatt.writeCharacteristic(client)
                }
            }
        } else {
            val srv = server ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                srv.notifyCharacteristicChanged(channel.device, chr, false, chunk) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    chr.value = chunk
                    srv.notifyCharacteristicChanged(channel.device, chr, false)
                }
            }
        }
    }

    // --- what arrives ------------------------------------------------------

    private fun received(channel: Channel, bytes: ByteArray) {
        for ((from, payload) in channel.frames.feed(bytes)) {
            if (channel.beacon.all { it == ZERO }) channel.beacon = from
            addresses[Ble.hex(from)] = channel.device.address
            _presence.tryEmit(Heard.Presence(from, 0))
            _messages.tryEmit(Heard.Message(from, payload))
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val data = result.scanRecord?.getManufacturerSpecificData(Ble.MANUFACTURER) ?: return
            if (data.size < 8) return
            val beacon = data.copyOf(8)
            if (beacon.contentEquals(me)) return
            addresses[Ble.hex(beacon)] = result.device.address
            _presence.tryEmit(Heard.Presence(beacon, result.rssi))
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "legacy scan failed: $errorCode")
            if (errorCode != SCAN_FAILED_ALREADY_STARTED) onBroken("Could not scan ($errorCode)")
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "legacy advertising failed: $errorCode")
            if (errorCode != ADVERTISE_FAILED_ALREADY_STARTED) onBroken("Could not advertise ($errorCode)")
        }
    }

    private val clientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val channel = channels[gatt.device.address] ?: return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.requestMtu(WANTED_MTU)
            } else {
                drop(channel)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            channels[gatt.device.address]?.mtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else DEFAULT_MTU
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val channel = channels[gatt.device.address] ?: return
            val chr = gatt.getService(SERVICE)?.getCharacteristic(DATA)
            if (chr == null) {
                drop(channel)
                return
            }
            gatt.setCharacteristicNotification(chr, true)
            val cccd = chr.getDescriptor(CCCD)
            if (cccd == null) {
                channel.ready.complete(true)
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                run {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(cccd)
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            channels[gatt.device.address]?.ready?.complete(true)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, chr: BluetoothGattCharacteristic, status: Int) {
            channels[gatt.device.address]?.sent?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, chr: BluetoothGattCharacteristic) {
            channels[gatt.device.address]?.let { received(it, chr.value ?: return) }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, chr: BluetoothGattCharacteristic, value: ByteArray) {
            channels[gatt.device.address]?.let { received(it, value) }
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // Their beacon arrives with their first frame; until then the
                // channel is known only by address.
                channels.getOrPut(device.address) {
                    Channel(
                        ByteArray(8),
                        device,
                        null
                    ).also { it.ready.complete(true) }
                }
            } else {
                channels[device.address]?.let { drop(it) }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            channels[device.address]?.mtu = mtu
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            chr: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            val channel = channels.getOrPut(device.address) {
                Channel(ByteArray(8), device, null).also { it.ready.complete(true) }
            }
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
            received(channel, value)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            channels[device.address]?.sent?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    companion object {
        private const val TAG = "OwuLink"
        private const val ZERO: Byte = 0

        /** 16-bit alias f077, so the advertisement stays inside 31 bytes. */
        val SERVICE: UUID = UUID.fromString("0000f077-0000-1000-8000-00805f9b34fb")
        val DATA: UUID = UUID.fromString("0000f078-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val DEFAULT_MTU = 23
        private const val WANTED_MTU = 517

        /** ATT write header. */
        private const val ATT_OVERHEAD = 3

        /** An attribute value is 512 bytes at most, whatever the MTU allows. */
        private const val MAX_ATTRIBUTE = 512
        private const val CONNECT_MS = 15_000L
        private const val WRITE_MS = 5_000L
    }
}
