package net.helcel.owu.peer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.helcel.owu.ble.Ble
import net.helcel.owu.ble.BlePermissions
import net.helcel.owu.ble.Link
import net.helcel.owu.store.Repo

/**
 * The app's presence on the air, all of it [Link]: an advertisement to be
 * found by, a connection to talk over. Only while the app is resumed
 * ([resume]/[pause]). No switch: an ask has to arrive wherever you are.
 *
 * While resumed, also follows adapter on/off.
 */
// The link holds the *application* context; stop() lets go of it.
@SuppressLint("StaticFieldLeak")
object PeerManager {

    data class State(
        val active: Boolean = false,
        /** Sessions by beacon hex, in order of appearance. */
        val peers: Map<String, PeerSession> = emptyMap(),
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** Adapter state, independent of [State.active]. */
    enum class Radio { UNSUPPORTED, NO_PERMISSION, OFF, TURNING_ON, ON }

    private val _radio = MutableStateFlow(Radio.OFF)
    val radio: StateFlow<Radio> = _radio

    private val _outcomes = MutableSharedFlow<Outcome>(extraBufferCapacity = 8)

    /** Tables that finished, wherever they did. A redeem is answered from a
     *  prompt with no trade screen in sight, so the news must travel. */
    val outcomes: SharedFlow<Outcome> = _outcomes

    private var scope: CoroutineScope? = null
    private var link: Link? = null
    private var table: PeerTable? = null
    private var listeningOn: Context? = null
    private val main = Handler(Looper.getMainLooper())

    private val adapterState = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> start(context)
                // Tear down early; the link dies with the adapter.
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> stop()
            }
            refresh(context)
        }
    }

    /** A verified session with the device whose key is [publicKey], if it is around. */
    fun readySession(publicKey: String): PeerSession? = table?.readySession(publicKey)

    /** Resumed: follow the adapter and start. */
    fun resume(context: Context) {
        val ctx = context.applicationContext
        if (listeningOn == null) {
            ContextCompat.registerReceiver(
                ctx, adapterState, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED, // system broadcasts still arrive
            )
            listeningOn = ctx
        }
        refresh(ctx)
        start(ctx)
    }

    /** Paused: stop and stop listening. */
    fun pause() {
        stopListening()
        stop()
    }

    private fun stopListening() {
        runCatching { listeningOn?.unregisterReceiver(adapterState) }
        listeningOn = null
    }

    private fun refresh(context: Context) {
        val adapter = BlePermissions.adapter(context)
        _radio.value = when {
            adapter == null -> Radio.UNSUPPORTED
            !BlePermissions.granted(context) -> Radio.NO_PERMISSION
            else -> when (adapter.state) {
                BluetoothAdapter.STATE_ON -> Radio.ON
                BluetoothAdapter.STATE_TURNING_ON -> Radio.TURNING_ON
                else -> Radio.OFF
            }
        }
    }

    fun start(context: Context) {
        if (_state.value.active) return
        val ctx = context.applicationContext
        if (!BlePermissions.granted(ctx)) return fail("Bluetooth permission not granted")
        val adapter = BlePermissions.adapter(ctx) ?: return fail("No Bluetooth on this device")
        if (!adapter.isEnabled) return fail("Bluetooth is off")

        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val me = Ble.beacon(Repo.me)
        lateinit var l: Link
        l = Link(ctx, adapter, me, onBroken = { reason -> main.post { standDown(l, reason) } })
        val myName = Repo.myName(ctx)
        val t = PeerTable(
            s, { PeerEngine(Repo.store, Repo.signer, myName) }, ::send,
            onOutcome = { _outcomes.tryEmit(it) })
        scope = s
        link = l
        table = t
        _state.value = State(active = true)

        s.launch {
            l.start()?.let { reason ->
                main.post { standDown(l, reason) }
                return@launch
            }
            launch { t.peers.collect { peers -> _state.update { it.copy(peers = peers) } } }
            launch { l.presence.collect { t.heard(it) } }
            launch { l.messages.collect { t.deliver(it) } }
            launch {
                while (isActive) {
                    delay(2_000)
                    t.prune()
                }
            }
        }
    }

    fun stop() {
        scope?.cancel()
        scope = null
        link?.stop()
        link = null
        table?.clear()
        table = null
        _state.value = State()
    }

    /** Opens the connection to [beacon]. True once messages can flow. */
    suspend fun select(beacon: ByteArray): Boolean = link?.connect(beacon) ?: false

    /** Done with that peer: hangs up. */
    fun release(beacon: ByteArray) {
        link?.disconnect(beacon)
    }

    private suspend fun send(beacon: ByteArray, bytes: ByteArray, dial: Boolean): Boolean =
        link?.send(beacon, bytes, dial) ?: false

    /** Link failed: reset so the next resume/adapter change/tap retries. */
    private fun standDown(l: Link, reason: String) {
        if (link !== l) return
        stop()
        fail(reason)
    }

    private fun fail(reason: String) = _state.update { it.copy(error = reason) }
}
