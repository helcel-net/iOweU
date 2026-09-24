package net.helcel.owu.peer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import net.helcel.owu.ble.Ble
import net.helcel.owu.ble.Heard
import net.helcel.owu.crypto.Hash

/**
 * The devices around us, one [PeerSession] each, keyed by beacon. Nothing
 * Android in it. A session starts on the first presence heard, or on a HELLO,
 * which names its sender's key.
 */
class PeerTable(
    private val scope: CoroutineScope,
    private val newEngine: () -> PeerEngine,
    /** Delivers bytes to the device with this beacon; true once acked. With `dial`, connects first if need be. */
    private val send: suspend (beacon: ByteArray, bytes: ByteArray, dial: Boolean) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Passed to every session: what a finished table amounted to. */
    private val onOutcome: (Outcome) -> Unit = {},
) {
    private val _peers = MutableStateFlow<Map<String, PeerSession>>(emptyMap())

    /** Sessions by beacon hex, in order of appearance. */
    val peers: StateFlow<Map<String, PeerSession>> = _peers
    private val lastHeard = mutableMapOf<String, Long>()

    /** Two sessions for one peer means two handshakes, which each side reads
     *  as the other restarting. So looking and creating are one step. */
    private val lock = Any()

    /** A verified session with the device whose key is [publicKey], if it is around. */
    fun readySession(publicKey: String): PeerSession? =
        _peers.value[Ble.beaconHex(publicKey)]?.takeIf { it.state.value.phase == PeerSession.Phase.READY }

    fun heard(seen: Heard.Presence): PeerSession = synchronized(lock) {
        val key = Hash.hex(seen.beacon)
        lastHeard[key] = clock()
        val existing = _peers.value[key]
        if (existing != null && existing.state.value.phase != PeerSession.Phase.GONE) {
            existing.heard(seen.rssi)
            return existing
        }
        val beacon = seen.beacon
        val session = PeerSession(beacon, newEngine(), { bytes, dial -> send(beacon, bytes, dial) }, scope, onOutcome)
        session.heard(seen.rssi)
        _peers.update { it + (key to session) }
        return session
    }

    /** Hands a message to its sender's session, making one if their HELLO introduces them. */
    fun deliver(inbound: Heard.Message) = synchronized(lock) {
        (sessionFor(inbound.from) ?: adopt(inbound))?.deliver(inbound.bytes)
    }

    /** From a device not yet heard: a HELLO names its key, which is enough. */
    private fun adopt(inbound: Heard.Message): PeerSession? {
        val hello = runCatching { PeerMessage.decode(inbound.bytes) }.getOrNull() as? PeerMessage.Hello ?: return null
        val beacon = runCatching { Ble.beacon(hello.identity.key) }.getOrNull() ?: return null
        if (!beacon.contentEquals(inbound.from)) return null
        return heard(Heard.Presence(beacon, 0))
    }

    private fun sessionFor(beacon: ByteArray): PeerSession? =
        _peers.value.values.firstOrNull { it.beacon.contentEquals(beacon) }

    /**
     * Drops sessions unheard for a while. Advertising is the *only* evidence
     * of presence: an open channel is not, since a phone that sleeps or dies
     * often leaves one behind with no disconnect ever arriving, and a session
     * propped up by that never goes. Tried it; worse than the wait it saved.
     */
    fun prune() {
        synchronized(lock) {
            val now = clock()
            val stale = _peers.value.filter { (k, p) ->
                p.state.value.phase == PeerSession.Phase.GONE || now - (lastHeard[k] ?: 0L) > GONE_MS
            }
            if (stale.isEmpty()) return
            stale.values.forEach { it.gone() }
            _peers.update { it - stale.keys }
        }
    }

    /** Ends every session. */
    fun clear() = synchronized(lock) {
        _peers.value.values.forEach { it.gone() }
        lastHeard.clear()
        _peers.value = emptyMap()
    }

    companion object {
        /** How long somebody stays "here" unheard. A live peer advertises many
         *  times a second, so this is many misses, not a close call. Short,
         *  because the dot and the trade button act on it. */
        const val GONE_MS = 12_000L
    }
}
