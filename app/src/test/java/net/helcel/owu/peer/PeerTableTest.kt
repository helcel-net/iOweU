package net.helcel.owu.peer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import net.helcel.owu.ble.Ble
import net.helcel.owu.ble.Heard
import net.helcel.owu.crypto.Hash
import net.helcel.owu.crypto.JvmSigner
import net.helcel.owu.store.IouStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Sessions come and go with presence, or with a HELLO that names its sender. */
@OptIn(ExperimentalCoroutinesApi::class)
class PeerTableTest {

    private fun store() = IouStore(Files.createTempDirectory("iou").toFile())

    @Test
    fun `presence opens one session per beacon and prune closes stale ones`() = runTest {
        var now = 0L
        val alice = JvmSigner()
        val table = PeerTable(this, { PeerEngine(store(), alice, "Alice") }, { _, _, _ -> true }, { now })
        val beacon = ByteArray(8) { 7 }
        val first = table.heard(Heard.Presence(beacon, -50))
        val again = table.heard(Heard.Presence(beacon, -40))
        assertSame(first, again)
        assertEquals(-40, first.state.value.rssi)
        assertEquals(1, table.peers.value.size)

        now += PeerTable.GONE_MS - 1
        table.prune()
        assertEquals(1, table.peers.value.size, "still fresh")

        now += 2
        table.prune()
        assertEquals(0, table.peers.value.size, "gone quiet")
        assertEquals(PeerSession.Phase.GONE, first.state.value.phase)
        advanceTimeBy(100)
    }

    @Test
    fun `a HELLO from an unheard device opens its session, anything else is dropped`() = runTest {
        val alice = JvmSigner()
        val bob = JvmSigner()
        val sent = mutableListOf<PeerMessage>()
        val table = PeerTable(this, { PeerEngine(store(), alice, "Alice") }, { _, bytes, _ -> sent += PeerMessage.decode(bytes); true })
        val bobBeacon = Ble.beacon(bob.publicKey)
        val hello = PeerEngine(store(), bob, "Bob").start().send.single()

        // A LIST_REQUEST from nobody we know: no session.
        table.deliver(Heard.Message(bobBeacon, PeerMessage.encode(PeerMessage.Table(bob.publicKey))))
        assertNull(table.peers.value[Hash.hex(bobBeacon)])

        // A HELLO whose key does not match the sender prefix: no session.
        table.deliver(Heard.Message(ByteArray(8) { 1 }, PeerMessage.encode(hello)))
        assertEquals(0, table.peers.value.size)

        // The real thing.
        table.deliver(Heard.Message(bobBeacon, PeerMessage.encode(hello)))
        val session = assertNotNull(table.peers.value[Hash.hex(bobBeacon)])
        advanceTimeBy(100)
        assertEquals(PeerSession.Phase.HANDSHAKE, session.state.value.phase)
        assertNotNull(sent.filterIsInstance<PeerMessage.Auth>().firstOrNull(), "answered their HELLO with AUTH: $sent")
        assertNull(table.readySession(bob.publicKey), "not verified yet")
        table.clear()
        advanceTimeBy(100)
    }
}
