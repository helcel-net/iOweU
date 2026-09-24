package net.helcel.owu.peer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import net.helcel.owu.crypto.JvmSigner
import net.helcel.owu.ledger.Ledger
import net.helcel.owu.ledger.Metadata
import net.helcel.owu.store.IouStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The session's timing and plumbing, with a fake air and virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class PeerSessionTest {

    private fun store() = IouStore(Files.createTempDirectory("iou").toFile())

    @Test
    fun `an unanswered HELLO is repeated until the peer answers`() = runTest {
        val alice = JvmSigner()
        val sent = mutableListOf<PeerMessage>()
        val session = PeerSession(
            ByteArray(8),
            PeerEngine(store(), alice, "Alice"),
            { bytes, _ -> sent += PeerMessage.decode(bytes); true },
            this
        )
        advanceTimeBy(100)
        assertEquals(1, sent.filterIsInstance<PeerMessage.Hello>().size)
        advanceTimeBy(20_000)
        val hellos = sent.filterIsInstance<PeerMessage.Hello>()
        assertTrue(hellos.size >= 3, "said hello again while unverified: ${hellos.size}")
        assertTrue(hellos.all { it.nonce == hellos.first().nonce }, "same nonce every time")

        // The peer finally answers: their HELLO, then AUTH over our nonce.
        val bob = JvmSigner()
        val theirs = PeerEngine(store(), bob, "Bob")
        val theirHello = theirs.start().send.single() as PeerMessage.Hello
        session.deliver(PeerMessage.encode(theirHello))
        val theirAuth = theirs.onMessage(hellos.first()).send.single()
        session.deliver(PeerMessage.encode(theirAuth))
        advanceTimeBy(100)
        assertEquals(PeerSession.Phase.READY, session.state.value.phase)
        assertEquals("Bob", session.state.value.peer?.name)

        val before = sent.size
        advanceTimeBy(30_000)
        assertEquals(before, sent.size, "nothing more once verified")
        session.gone()
    }

    @Test
    fun `an unanswered hello is not worth saying, a lost action is`() = runTest {
        val alice = JvmSigner()
        val bob = JvmSigner()
        val store = store()
        // A link that swallows everything, but still shows us what we said.
        val sent = mutableListOf<PeerMessage>()
        val session = PeerSession(
            ByteArray(8),
            PeerEngine(store, alice, "Alice"),
            { bytes, _ -> sent += PeerMessage.decode(bytes); false },
            this
        )
        advanceTimeBy(100)
        assertTrue(session.state.value.log.isEmpty(), "a hello nobody answers is ordinary: ${session.state.value.log}")

        // Their side answers ours, so the peer is verified even though nothing we send arrives.
        val hello = sent.filterIsInstance<PeerMessage.Hello>().first()
        val theirs = PeerEngine(store(), bob, "Bob")
        session.deliver(PeerMessage.encode(theirs.start().send.single()))
        session.deliver(PeerMessage.encode(theirs.onMessage(hello).send.single()))
        advanceTimeBy(100)
        assertEquals(PeerSession.Phase.READY, session.state.value.phase)

        // Now something the user did goes missing: that is worth saying.
        val OwU = Ledger.issue(alice, Metadata("1 Coffee"))
        store.put(OwU)
        session.put(listOf(OwU))
        advanceTimeBy(1000)
        assertTrue(session.state.value.log.any { it.startsWith("Not delivered") }, session.state.value.log.toString())
        assertNull(session.state.value.waiting)
        session.gone()
    }

    /**
     * What the screen says it was. A redemption reaches the holder as a gift
     * given and a receipt returned, two steps apart, and the debtor as a gift
     * taken and a promise closed in the same one - and it is one thing that
     * happened, named once, on both phones.
     */
    @Test
    fun `a redemption is announced once on each side, as a redemption`() = runTest {
        val alice = JvmSigner()   // holds it, and asks
        val bob = JvmSigner()     // wrote it, and honours it
        val aStore = store()
        val bStore = store()
        val OwU = Ledger.transfer(Ledger.issue(bob, Metadata("1 Beer")), bob, alice.publicKey)
        aStore.put(OwU); bStore.put(OwU)

        val aSaid = mutableListOf<Outcome>()
        val bSaid = mutableListOf<Outcome>()
        lateinit var them: PeerSession
        val us = PeerSession(
            ByteArray(8) { 1 }, PeerEngine(aStore, alice, "Alice"),
            { bytes, _ -> them.deliver(bytes); true }, this, { aSaid += it })
        them = PeerSession(
            ByteArray(8) { 2 }, PeerEngine(bStore, bob, "Bob"),
            { bytes, _ -> us.deliver(bytes); true }, this, { bSaid += it })
        advanceTimeBy(1_000)
        assertEquals(PeerSession.Phase.READY, us.state.value.phase, "handshake: ${us.state.value.log}")

        // Alice asks; Bob says yes, as he would from the prompt.
        us.offer(listOf(OwU))
        advanceTimeBy(1_000)
        them.accept()
        advanceTimeBy(1_000)

        // Not "Gave 1 Beer" and then "1 Beer redeemed": once, and as what it was.
        assertEquals(1, aSaid.size, "the asker was told once: $aSaid")
        assertEquals(1, bSaid.size, "the debtor was told once: $bSaid")
        for (said in listOf(aSaid, bSaid)) {
            assertEquals(listOf("1 Beer"), said.single().redeemed.map { it.metadata.title })
            assertTrue(said.single().gave.isEmpty() && said.single().got.isEmpty(), "${said.single()}")
        }
        assertEquals(bob.publicKey, aSaid.single().peer)
        us.gone(); them.gone()
    }

    /** An ordinary gift is a gift, and only the two of them hear about it. */
    @Test
    fun `giving is announced as given on one side and got on the other`() = runTest {
        val alice = JvmSigner()
        val bob = JvmSigner()
        val aStore = store()
        val OwU = Ledger.issue(alice, Metadata("1 Coffee"))
        aStore.put(OwU)

        val aSaid = mutableListOf<Outcome>()
        val bSaid = mutableListOf<Outcome>()
        lateinit var them: PeerSession
        val us = PeerSession(
            ByteArray(8) { 1 }, PeerEngine(aStore, alice, "Alice"),
            { bytes, _ -> them.deliver(bytes); true }, this, { aSaid += it })
        them = PeerSession(
            ByteArray(8) { 2 }, PeerEngine(store(), bob, "Bob"),
            { bytes, _ -> us.deliver(bytes); true }, this, { bSaid += it })
        advanceTimeBy(1_000)

        us.offer(listOf(OwU))
        advanceTimeBy(1_000)
        them.accept()
        advanceTimeBy(1_000)

        assertEquals(listOf("1 Coffee"), aSaid.single().gave.map { it.metadata.title })
        assertEquals(listOf("1 Coffee"), bSaid.single().got.map { it.metadata.title })
        us.gone(); them.gone()
    }
}
