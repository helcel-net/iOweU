package net.helcel.owu.peer

import net.helcel.owu.crypto.JvmSigner
import net.helcel.owu.crypto.Signer
import net.helcel.owu.helper.IdentityCard
import net.helcel.owu.ledger.Ledger
import net.helcel.owu.ledger.Metadata
import net.helcel.owu.ledger.Status
import net.helcel.owu.ledger.Iou
import net.helcel.owu.ledger.Verifier
import java.util.UUID
import net.helcel.owu.store.Contact
import net.helcel.owu.store.IouStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PeerEngineTest {

    /** A device: its own store on disk and its engine for one session. */
    private class Device(val signer: Signer = JvmSigner(), val name: String) {
        val store = IouStore(Files.createTempDirectory("iou").toFile())
        lateinit var engine: PeerEngine
        val events = mutableListOf<PeerEngine.Event>()
        val outbox = ArrayDeque<PeerMessage>()
        fun session() {
            events.clear(); outbox.clear(); engine = PeerEngine(store, signer, name); take(engine.start())
        }

        fun take(step: PeerEngine.Step) {
            outbox += step.send; events += step.events
        }

        fun act(block: PeerEngine.() -> PeerEngine.Step) = take(engine.block())
        fun iou(id: String) = store.ious.value.getValue(id)
        fun state(id: String) = Verifier.verify(iou(id)).stateOrNull!!
        inline fun <reified E : PeerEngine.Event> only(): E = events.filterIsInstance<E>().single()
        fun clean() = assertTrue(events.none { it is PeerEngine.Event.Failed }, "$name: $events")
    }

    /** Ferries messages both ways, byte-encoded like the real link, until nothing moves. */
    private fun pump(a: Device, b: Device) {
        while (a.outbox.isNotEmpty() || b.outbox.isNotEmpty()) {
            a.outbox.removeFirstOrNull()?.let { b.take(b.engine.onMessage(PeerMessage.decode(PeerMessage.encode(it)))) }
            b.outbox.removeFirstOrNull()?.let { a.take(a.engine.onMessage(PeerMessage.decode(PeerMessage.encode(it)))) }
        }
    }

    private fun connect(a: Device, b: Device) {
        a.session(); b.session(); pump(a, b)
    }

    private val alice = Device(name = "Alice")
    private val bob = Device(name = "Bob")
    private val carol = Device(name = "Carol")

    @Test
    fun `peers authenticate and nothing else happens`() {
        connect(alice, bob)
        assertEquals("Bob", alice.engine.peer?.name)
        assertEquals(bob.signer.publicKey, alice.engine.peer?.key)
        assertEquals("Alice", bob.engine.peer?.name)
        assertEquals(1, alice.events.size); assertEquals(1, bob.events.size)
        alice.clean(); bob.clean()
    }

    @Test
    fun `a lost first HELLO is recovered by asking again, and repeats are harmless`() {
        alice.session(); bob.session()
        // Alice's HELLO never reaches Bob (his radio was not up yet).
        alice.outbox.removeFirst()
        pump(alice, bob)
        // Bob's HELLO reached Alice, who answered; Bob got an AUTH he cannot place,
        // and never answered Alice's HELLO because he never saw it. Neither is verified.
        assertNull(alice.engine.peer)
        assertNull(bob.engine.peer)
        assertTrue(bob.events.none { it is PeerEngine.Event.Failed }, "an early AUTH is kept, not an error")

        // Alice says hello again (same nonce); now both sides complete.
        alice.act { reintroduce() }
        pump(alice, bob)
        assertEquals("Bob", alice.engine.peer?.name)
        assertEquals("Alice", bob.engine.peer?.name)
        assertTrue(alice.events.none { it is PeerEngine.Event.Restarted })
        assertTrue(bob.events.none { it is PeerEngine.Event.Failed }, bob.events.toString())

        // A third hello from either side, once verified, only draws a fresh AUTH - no restart.
        bob.act { reintroduce() }
        pump(alice, bob)
        assertTrue(alice.events.none { it is PeerEngine.Event.Restarted })
        assertTrue(bob.events.none { it is PeerEngine.Event.Restarted })
        assertEquals(1, alice.events.count { it is PeerEngine.Event.PeerIdentified })
    }

    @Test
    fun `a peer that restarts with a new nonce is verified afresh`() {
        connect(alice, bob)
        bob.session() // Bob's app restarted: new engine, new nonce
        pump(alice, bob)
        assertTrue(alice.events.any { it is PeerEngine.Event.Restarted })
        assertEquals("Bob", alice.engine.peer?.name)
        assertEquals("Alice", bob.engine.peer?.name)
        alice.clean(); bob.clean()
    }

    @Test
    fun `an impostor claiming another key is rejected`() {
        val mallory = Device(name = "Bob")
        alice.session()
        val aliceHello = alice.outbox.removeFirst() as PeerMessage.Hello
        alice.take(
            alice.engine.onMessage(
                PeerMessage.Hello(
                    IdentityCard(name = "Bob", key = bob.signer.publicKey),
                    nonce = "00"
                )
            )
        )
        assertIs<PeerMessage.Auth>(alice.outbox.removeFirst())
        val forgedAuth =
            PeerMessage.Auth(mallory.signer.sign(PeerEngine.authBytes(aliceHello.nonce, bob.signer.publicKey)))
        alice.take(alice.engine.onMessage(forgedAuth))
        assertNull(alice.engine.peer)
        assertIs<PeerEngine.Event.Failed>(alice.events.single())
        // Anything they send while unproven is answered with who we are - the
        // same card we offer anybody who connects - and with nothing else. No
        // table is taken, no OwU is kept, nothing of ours goes out.
        val leak = alice.engine.onMessage(
            PeerMessage.Table(
                bob.signer.publicKey,
                listOf(Ledger.issue(bob.signer, metadata = Metadata("x")))
            )
        )
        assertTrue(leak.events.isEmpty(), "${leak.events}")
        assertIs<PeerMessage.Hello>(leak.send.single())
        assertNull(alice.engine.peer)
        assertTrue(alice.store.ious.value.isEmpty(), "nothing of theirs was kept")
    }

    @Test
    fun `an acceptance stands on its own when the table before it is lost`() {
        val promise = Ledger.issue(alice.signer, metadata = Metadata("1 Coffee"))
        alice.store.put(promise)
        connect(alice, bob)
        alice.act { put(listOf(promise)) }
        // The TABLE never arrives: drop it on the floor as the air would.
        val table = alice.outbox.removeFirst()
        assertIs<PeerMessage.Table>(table)
        alice.act { accept() }
        pump(alice, bob)

        assertEquals(1, bob.engine.table().theirs.size, "bob has their side from the acceptance alone")
        assertTrue(bob.engine.table().theyAccepted, "and knows they have said yes")
        bob.act { accept() }; pump(alice, bob)
        assertEquals(bob.signer.publicKey, bob.state(promise.id).holder, "it still changes hands")
        assertEquals(alice.iou(promise.id), bob.iou(promise.id))
        alice.clean(); bob.clean()
    }

    // --- bundles -----------------------------------------------------------

    @Test
    fun `a bundle of many minted promises is given as one`() {
        connect(alice, bob)
        val beer = Metadata("1 Beer")
        val hug = Metadata("1 Heavy Hug")
        val trip = Metadata("1 Surprise trip")
        alice.act { put(mint = List(5) { beer } + List(2) { hug } + listOf(trip)) }
        pump(alice, bob)
        assertEquals(8, bob.engine.table().theirs.size, "bob sees the whole bundle")
        assertEquals(5, bob.engine.table().theirs.count { it.metadata == beer })
        assertTrue(bob.store.ious.value.isEmpty(), "nothing is kept until both accept")

        alice.act { accept() }; pump(alice, bob)
        bob.act { accept() }; pump(alice, bob)
        assertEquals(8, bob.store.ious.value.size, "all eight arrive")
        assertTrue(bob.store.ious.value.values.all { bob.state(it.id).holder == bob.signer.publicKey })
        assertTrue(alice.store.ious.value.values.all { alice.state(it.id).debtor == alice.signer.publicKey })
        assertEquals(8, (alice.only<PeerEngine.Event.Done>()).gave.size)
        alice.clean(); bob.clean()
    }

    @Test
    fun `bundles swap atomically, every OwU or none`() {
        val x1 = Ledger.issue(carol.signer, creditor = alice.signer.publicKey, metadata = Metadata("X1"))
        val x2 = Ledger.issue(carol.signer, creditor = alice.signer.publicKey, metadata = Metadata("X2"))
        val y1 = Ledger.issue(carol.signer, creditor = bob.signer.publicKey, metadata = Metadata("Y1"))
        val y2 = Ledger.issue(carol.signer, creditor = bob.signer.publicKey, metadata = Metadata("Y2"))
        val y3 = Ledger.issue(carol.signer, creditor = bob.signer.publicKey, metadata = Metadata("Y3"))
        listOf(x1, x2).forEach { alice.store.put(it) }
        listOf(y1, y2, y3).forEach { bob.store.put(it) }
        connect(alice, bob)
        alice.act { put(listOf(x1, x2)) }; pump(alice, bob)
        bob.act { put(listOf(y1, y2, y3)) }; pump(alice, bob)
        alice.act { accept() }; pump(alice, bob)
        bob.act { accept() }; pump(alice, bob)

        // Two for three: everything crossed, and both sides agree on each chain.
        listOf(x1, x2).forEach { assertEquals(bob.signer.publicKey, alice.state(it.id).holder, it.metadata.title) }
        listOf(y1, y2, y3).forEach {
            assertEquals(
                alice.signer.publicKey,
                alice.state(it.id).holder,
                it.metadata.title
            )
        }
        listOf(x1, x2, y1, y2, y3).forEach { assertEquals(alice.iou(it.id), bob.iou(it.id), it.metadata.title) }
        // One agreement covers the lot, so every block carries the same id.
        val ids = listOf(x1, x2, y1, y2, y3)
            .map { (alice.iou(it.id).head as net.helcel.owu.ledger.Block.Transfer).agreement!!.id }
            .toSet()
        assertEquals(1, ids.size, "one agreement for the whole bundle")
        alice.clean(); bob.clean()
    }

    @Test
    fun `a bundle handed home to its author closes all of it`() {
        connect(alice, bob)
        alice.act { put(mint = List(3) { Metadata("1 Beer") }) }; pump(alice, bob)
        alice.act { accept() }; pump(alice, bob)
        bob.act { accept() }; pump(alice, bob)
        val beers = bob.store.ious.value.keys.toList()
        assertEquals(3, beers.size)

        // Bob hands all three back; Alice owes them, so all three close.
        bob.act { put(beers.map { bob.iou(it) }) }; pump(alice, bob)
        bob.act { accept() }; pump(alice, bob)
        alice.act { accept() }; pump(alice, bob)
        beers.forEach {
            assertEquals(Status.REDEEMED, alice.state(it).status, "alice closed it")
            assertEquals(Status.REDEEMED, bob.state(it).status, "bob has the receipt")
        }
        alice.clean(); bob.clean()
    }

    // --- the table ---------------------------------------------------------

    @Test
    fun `a promise put down and accepted by both becomes theirs`() {
        val promise = Ledger.issue(alice.signer, metadata = Metadata("1 Coffee"))
        alice.store.put(promise)
        connect(alice, bob)
        alice.act { put(listOf(promise)) }
        pump(alice, bob)
        assertEquals(promise.id, bob.engine.table().theirs.singleOrNull()?.id, "bob sees what alice put down")
        assertNull(bob.store.ious.value[promise.id], "nothing is kept until both accept")

        bob.act { accept() }
        pump(alice, bob)
        assertFalse(alice.engine.table().accepted, "alice has not said yes yet")
        assertTrue(alice.engine.table().theyAccepted)
        assertNull(bob.store.ious.value[promise.id], "one yes is not enough")

        alice.act { accept() }
        pump(alice, bob)
        for (d in listOf(alice, bob)) {
            assertEquals(bob.signer.publicKey, d.state(promise.id).holder, "${d.name}: holder")
            assertEquals(alice.signer.publicKey, d.state(promise.id).debtor, "${d.name}: debtor")
            assertTrue(d.engine.table().empty, "${d.name}: table cleared")
            d.clean()
        }
        assertEquals(alice.iou(promise.id), bob.iou(promise.id))
        assertEquals(promise.id, alice.only<PeerEngine.Event.Done>().gave.single().id)
        assertEquals(promise.id, bob.only<PeerEngine.Event.Done>().got.single().id)
    }

    @Test
    fun `either side can accept first and an OwU already held travels the same way`() {
        val x = Ledger.issue(carol.signer, creditor = alice.signer.publicKey, metadata = Metadata("carol owes alice"))
        alice.store.put(x)
        connect(alice, bob)
        alice.act { put(listOf(x)) }; pump(alice, bob)
        alice.act { accept() }; pump(alice, bob)
        assertEquals(alice.signer.publicKey, alice.state(x.id).holder, "nothing moves on one yes")
        bob.act { accept() }; pump(alice, bob)
        assertEquals(bob.signer.publicKey, bob.state(x.id).holder)
        assertEquals(alice.iou(x.id), bob.iou(x.id))
        alice.clean(); bob.clean()
    }

    @Test
    fun `two ious on the table swap atomically, whoever accepts first`() {
        val x = Ledger.issue(carol.signer, creditor = alice.signer.publicKey, metadata = Metadata("X"))
        val y = Ledger.issue(carol.signer, creditor = bob.signer.publicKey, metadata = Metadata("Y"))
        alice.store.put(x); bob.store.put(y)
        connect(alice, bob)
        alice.act { put(listOf(x)) }; pump(alice, bob)
        bob.act { put(listOf(y)) }; pump(alice, bob)
        assertEquals(y.id, alice.engine.table().theirs.singleOrNull()?.id)
        assertEquals(x.id, bob.engine.table().theirs.singleOrNull()?.id)

        bob.act { accept() }; pump(alice, bob)
        assertEquals(alice.signer.publicKey, alice.state(x.id).holder, "one yes moves nothing")
        alice.act { accept() }; pump(alice, bob)

        for (d in listOf(alice, bob)) {
            assertEquals(bob.signer.publicKey, d.state(x.id).holder, "${d.name}: x")
            assertEquals(alice.signer.publicKey, d.state(y.id).holder, "${d.name}: y")
            assertEquals(2, d.iou(x.id).chain.size)
            val done = d.only<PeerEngine.Event.Done>()
            assertNotNull(done.gave.singleOrNull()); assertNotNull(done.got.singleOrNull())
            d.clean()
        }
        assertEquals(alice.iou(x.id), bob.iou(x.id))
        assertEquals(alice.iou(y.id), bob.iou(y.id))
    }

    @Test
    fun `changing the table withdraws both yeses`() {
        val x = Ledger.issue(carol.signer, creditor = alice.signer.publicKey, metadata = Metadata("X"))
        val other = Ledger.issue(carol.signer, creditor = alice.signer.publicKey, metadata = Metadata("other"))
        alice.store.put(x); alice.store.put(other)
        connect(alice, bob)
        alice.act { put(listOf(x)) }; pump(alice, bob)
        bob.act { accept() }; pump(alice, bob)
        assertTrue(alice.engine.table().theyAccepted)

        alice.act { put(listOf(other)) }; pump(alice, bob)
        assertFalse(alice.engine.table().theyAccepted, "their yes was to the old table")
        assertFalse(bob.engine.table().accepted, "and bob knows it")
        assertEquals(other.id, bob.engine.table().theirs.singleOrNull()?.id)

        // The yes they gave earlier cannot be replayed against the new table.
        alice.take(alice.engine.onMessage(PeerMessage.Accept(bob.signer.publicKey, "stale-deal")))
        assertIs<PeerEngine.Event.Failed>(alice.events.last())
        assertEquals(alice.signer.publicKey, alice.state(other.id).holder)
    }

    @Test
    fun `an empty table cannot be accepted, and an OwU the other side does not hold is refused`() {
        connect(alice, bob)
        assertTrue(runCatching { alice.engine.accept() }.isFailure, "nothing on the table")
        val bobs = Ledger.issue(carol.signer, creditor = bob.signer.publicKey, metadata = Metadata("bob's"))
        bob.take(bob.engine.onMessage(PeerMessage.Table(alice.signer.publicKey, listOf(bobs))))
        assertIs<PeerEngine.Event.Failed>(bob.events.last())
        assertTrue(bob.engine.table().empty)
    }

    @Test
    fun `a swap signed by someone else is refused`() {
        val x = Ledger.issue(carol.signer, creditor = alice.signer.publicKey, metadata = Metadata("X"))
        val y = Ledger.issue(carol.signer, creditor = bob.signer.publicKey, metadata = Metadata("Y"))
        alice.store.put(x); bob.store.put(y)
        connect(alice, bob)
        alice.act { put(listOf(x)) }; pump(alice, bob)
        bob.act { put(listOf(y)) }; pump(alice, bob)
        // Carol signs a proposal that claims to be Bob's.
        val forged = Ledger.proposeExchange(listOf(y), listOf(x), object : Signer {
            override val publicKey = bob.signer.publicKey
            override fun sign(data: ByteArray) = carol.signer.sign(data)
        })
        val deal = (bob.engine.accept().send.single() as PeerMessage.Accept).deal
        alice.take(alice.engine.onMessage(PeerMessage.Accept(bob.signer.publicKey, deal, listOf(y), forged)))
        assertIs<PeerEngine.Event.Failed>(alice.events.last())
        assertEquals(alice.signer.publicKey, alice.state(x.id).holder)
    }

    @Test
    fun `a name is remembered once the key behind it is proven, and never overwrites yours`() {
        connect(alice, bob)
        assertEquals("Bob", alice.store.metName(bob.signer.publicKey), "learned from the handshake")
        assertEquals("Alice", bob.store.metName(alice.signer.publicKey))
        assertNull(alice.store.nameFor(bob.signer.publicKey), "but not made a contact by itself")

        // Having named him yourself, that is what counts; his own claim is
        // still kept, and is not allowed to overwrite the name you chose.
        alice.store.putContact(Contact(bob.signer.publicKey, "Bobby"))
        val liar = Device(signer = bob.signer, name = "Someone Else")
        connect(alice, liar)
        assertEquals("Bobby", alice.store.nameFor(bob.signer.publicKey))
        assertEquals("Someone Else", alice.store.metName(bob.signer.publicKey))
    }

    @Test
    fun `a promise kept ready mints a fresh OwU every time it is handed over`() {
        val beer = Metadata("1 Beer")
        connect(alice, bob)
        alice.act { putNew(beer) }
        pump(alice, bob)
        val first = alice.engine.table().mine.singleOrNull()!!.id
        bob.act { accept() }; alice.act { accept() }
        pump(alice, bob)
        assertEquals(bob.signer.publicKey, bob.state(first).holder)

        // The same promise again: a different OwU, signed afresh.
        alice.act { putNew(beer) }
        pump(alice, bob)
        val second = alice.engine.table().mine.singleOrNull()!!.id
        assertNotEquals(first, second, "each pour is its own OwU")
        bob.act { accept() }; alice.act { accept() }
        pump(alice, bob)
        assertEquals(bob.signer.publicKey, bob.state(second).holder)
        assertEquals(2, bob.store.ious.value.count { it.value.metadata == beer }, "bob holds two beers")
        alice.clean(); bob.clean()
    }

    @Test
    fun `a minted OwU that never leaves is dropped again`() {
        connect(alice, bob)
        alice.act { putNew(Metadata("1 Beer")) }
        pump(alice, bob)
        val minted = alice.engine.table().mine.singleOrNull()!!.id
        assertNotNull(alice.store.ious.value[minted])

        // Changing my mind takes it off the table and out of the ledger.
        alice.act { put() }
        pump(alice, bob)
        assertNull(alice.store.ious.value[minted], "nothing was promised, so nothing is kept")
        assertNull(bob.store.ious.value[minted], "and bob never kept it either")

        // Parting company does the same for one still on the table.
        alice.act { putNew(Metadata("1 Beer")) }
        pump(alice, bob)
        val second = alice.engine.table().mine.singleOrNull()!!.id
        alice.engine.abandon()
        assertNull(alice.store.ious.value[second])
        alice.clean(); bob.clean()
    }

    @Test
    fun `an OwU signed over to two people is caught at the table, not just at redemption`() {
        // Bob signs the same OwU over to Carol and to Dave, offline.
        val dave = Device(name = "Dave")
        val OwU = Ledger.issue(alice.signer, creditor = bob.signer.publicKey, metadata = Metadata("1 Coffee"))
        val toCarol = Ledger.transfer(OwU, bob.signer, carol.signer.publicKey)
        val toDave = Ledger.transfer(OwU, bob.signer, dave.signer.publicKey)
        carol.store.put(toCarol); dave.store.put(toDave)

        // Carol has seen her copy. Dave now offers his at the table.
        connect(carol, dave)
        dave.act { put(listOf(toDave)) }
        pump(carol, dave)
        assertTrue(carol.engine.table().conflict, "carol can see the two histories herself")
        assertTrue(runCatching { carol.engine.accept() }.isFailure, "and cannot accept against it")
        assertEquals(carol.signer.publicKey, carol.state(OwU.id).holder, "her copy is untouched")

        // Offering an OwU at a head that has already moved on is the same
        // trick with one copy: Dave gives his OwU away, then offers the
        // version from before he did.
        val given = Ledger.transfer(toDave, dave.signer, carol.signer.publicKey)
        carol.store.put(given)
        dave.act { put(listOf(toDave)) }
        pump(carol, dave)
        assertTrue(carol.engine.table().conflict, "carol knows that OwU has moved on")

        // An OwU nobody has a second copy of raises nothing.
        val clean = Ledger.issue(dave.signer, metadata = Metadata("1 Clean"))
        dave.store.put(clean)
        dave.act { put(listOf(clean)) }
        pump(carol, dave)
        assertFalse(carol.engine.table().conflict)
        carol.clean()
    }

    // --- redeem ------------------------------------------------------------

    @Test
    fun `redeeming is handing the OwU home, and the debtor closes it`() {
        val iou = handedOver(alice, bob.signer.publicKey, Metadata("1 Coffee"))
        alice.store.put(iou); bob.store.put(iou)
        connect(alice, bob)

        // Bob puts alice's own promise on the table; nothing else is needed.
        bob.act { put(listOf(iou)) }
        pump(alice, bob)
        alice.act { accept() }; bob.act { accept() }
        pump(alice, bob)

        for (d in listOf(alice, bob)) {
            assertEquals(Status.REDEEMED, d.state(iou.id).status, "${d.name}")
            assertEquals(alice.signer.publicKey, d.state(iou.id).holder, "${d.name}: home")
            d.clean()
        }
        assertEquals(alice.iou(iou.id), bob.iou(iou.id))
        alice.only<PeerEngine.Event.Redeemed>()
        bob.only<PeerEngine.Event.Redeemed>()
    }

    @Test
    fun `a promise that comes home in a swap closes itself, and they are told`() {
        val mine = handedOver(alice, bob.signer.publicKey, Metadata("1 Coffee"), id = "a-mine")
        val theirs = handedOver(carol.signer, alice.signer.publicKey, Metadata("1 Tea"), id = "b-theirs")
        alice.store.put(mine); alice.store.put(theirs); bob.store.put(mine)
        connect(alice, bob)
        alice.act { put(listOf(theirs)) }; pump(alice, bob)
        bob.act { put(listOf(mine)) }; pump(alice, bob)
        alice.act { accept() }; pump(alice, bob)
        bob.act { accept() }; pump(alice, bob)

        // Her own promise came back in the swap: nobody else could ever be
        // asked for it, so it is spent - on both phones.
        for (d in listOf(alice, bob)) {
            assertEquals(Status.REDEEMED, d.state(mine.id).status, "${d.name}: the coffee")
            assertEquals(Status.ACTIVE, d.state(theirs.id).status, "${d.name}: the tea carries on")
        }
        assertEquals(alice.iou(mine.id), bob.iou(mine.id))
        assertEquals(bob.signer.publicKey, alice.state(theirs.id).holder, "the tea went to bob")
        alice.only<PeerEngine.Event.Redeemed>(); bob.only<PeerEngine.Event.Redeemed>()
        alice.clean(); bob.clean()
    }

    @Test
    fun `an OwU that was already redeemed cannot be put on the table again`() {
        val iou = handedOver(alice, bob.signer.publicKey, Metadata("1 Coffee"))
        val home = Ledger.transfer(iou, bob.signer, alice.signer.publicKey)
        val closed = Ledger.redeem(home, alice.signer)
        bob.store.put(closed)
        connect(alice, bob)
        assertTrue(runCatching { bob.engine.put(listOf(closed)) }.isFailure, "spent")
    }

    @Test
    fun `a double-spent branch is refused by the debtor once one copy has come home`() {
        val dave = Device(name = "Dave")
        val iou = handedOver(alice, bob.signer.publicKey, Metadata("1 Coffee"))
        alice.store.put(iou)
        // Bob signs the same OwU over to Carol and to Dave, offline.
        val toCarol = Ledger.transfer(iou, bob.signer, carol.signer.publicKey)
        val toDave = Ledger.transfer(iou, bob.signer, dave.signer.publicKey)
        carol.store.put(toCarol); dave.store.put(toDave)

        // Carol gets there first and redeems hers.
        connect(alice, carol)
        carol.act { put(listOf(toCarol)) }; pump(alice, carol)
        carol.act { accept() }; alice.act { accept() }; pump(alice, carol)
        assertEquals(Status.REDEEMED, carol.state(iou.id).status)

        // Dave shows up with the other branch: alice sees the contradiction
        // on the table and cannot accept it.
        connect(alice, dave)
        dave.act { put(listOf(toDave)) }; pump(alice, dave)
        assertTrue(alice.engine.table().conflict, "alice holds a copy that says otherwise")
        assertTrue(runCatching { alice.engine.accept() }.isFailure)
        assertEquals(Status.REDEEMED, alice.state(iou.id).status, "hers is the one that counted")
        alice.clean(); carol.clean()
    }

    /** A promise written by [debtor] and handed to [holder], as every moved OwU is. */
    /**
     * A refusal is not silence. Turning down what is on the table clears it
     * on both sides, so the one who asked stops waiting and can ask again.
     */
    @Test
    fun `refusing the table tells the other side and clears it for both`() {
        val iou = handedOver(bob, alice.signer.publicKey, Metadata("1 Beer"))
        alice.store.put(iou); bob.store.put(iou)
        connect(alice, bob)

        // Alice asks Bob to redeem, as the Redeem button does.
        alice.act { put(listOf(iou)) }
        alice.act { accept() }
        pump(alice, bob)
        assertTrue(bob.engine.table().theirs.isNotEmpty(), "the ask is on his table")

        bob.events.clear(); alice.events.clear()
        bob.act { decline(PeerMessage.Asked.TABLE) }
        pump(alice, bob)

        assertEquals(PeerMessage.Asked.TABLE, alice.only<PeerEngine.Event.Declined>().to)
        for (d in listOf(alice, bob)) {
            assertTrue(d.engine.table().mine.isEmpty() && d.engine.table().theirs.isEmpty(), "${d.name}: table cleared")
            d.clean()
        }
        // Nothing moved: the promise is still hers, still open.
        assertEquals(Status.ACTIVE, alice.state(iou.id).status)
        assertEquals(alice.signer.publicKey, alice.state(iou.id).holder)

        // And she may ask again.
        alice.act { put(listOf(iou)) }
        alice.act { accept() }
        pump(alice, bob)
        bob.act { accept() }
        pump(alice, bob)
        assertEquals(Status.REDEEMED, alice.state(iou.id).status)
    }

    /** Turning down an invitation says so, and touches nothing. */
    @Test
    fun `refusing an invitation tells the other side`() {
        connect(alice, bob)
        alice.act { invite() }
        pump(alice, bob)
        alice.events.clear()
        bob.act { decline(PeerMessage.Asked.INVITE) }
        pump(alice, bob)
        assertEquals(PeerMessage.Asked.INVITE, alice.only<PeerEngine.Event.Declined>().to)
        alice.clean(); bob.clean()

        // A refusal signed with somebody else's key is not theirs to send.
        val forged = alice.engine.onMessage(PeerMessage.Decline(carol.signer.publicKey, PeerMessage.Asked.TABLE))
        assertIs<PeerEngine.Event.Failed>(forged.events.single())
    }

    /** Opening a table reaches the other person, and only a known one. */
    @Test
    fun `an invitation to the table arrives as an invitation`() {
        connect(alice, bob)
        alice.events.clear(); bob.events.clear()
        alice.act { invite() }
        pump(alice, bob)
        assertEquals(alice.signer.publicKey, bob.only<PeerEngine.Event.Invited>().card.key)
        assertTrue(alice.events.isEmpty(), "nothing comes back: ${alice.events}")
        bob.clean()

        // Nothing on the table changed, and nobody has accepted anything.
        assertTrue(bob.engine.table().mine.isEmpty() && bob.engine.table().theirs.isEmpty())

        // An invitation signed with somebody else's key is not theirs to send.
        val forged = bob.engine.onMessage(PeerMessage.Invite(carol.signer.publicKey))
        assertIs<PeerEngine.Event.Failed>(forged.events.single())
    }

    /**
     * The deadlock that made a redeem sit on "waiting for them to accept"
     * for ever: one radio restarts, so one side has never met the other,
     * while that other side goes on believing they were introduced and talks
     * as though they had been. Neither ever says hello again.
     */
    @Test
    fun `a peer that no longer knows us is told who we are, and the ask can be made again`() {
        val iou = handedOver(bob, alice.signer.publicKey, Metadata("1 Beer"))
        alice.store.put(iou); bob.store.put(iou)
        connect(alice, bob)

        // Bob's radio restarts: a new engine that has never met Alice, whose
        // hello never reaches her. She asks to redeem as though all were well.
        bob.session()
        bob.outbox.clear()
        alice.act { put(listOf(iou)) }
        alice.act { accept() }
        pump(alice, bob)

        // Nobody is stuck: they have introduced themselves afresh.
        assertEquals(alice.signer.publicKey, bob.engine.peer?.key, "bob: ${bob.events}")
        assertEquals(bob.signer.publicKey, alice.engine.peer?.key, "alice: ${alice.events}")
        // Her table went with the restart, which is why the ask is kept
        // until it is seen on their table rather than until it is sent.
        assertTrue(alice.engine.table().mine.isEmpty(), "the table did not survive the restart")

        alice.act { put(listOf(iou)) }
        alice.act { accept() }
        pump(alice, bob)
        bob.act { accept() }
        pump(alice, bob)
        assertEquals(Status.REDEEMED, alice.state(iou.id).status)
        assertEquals(Status.REDEEMED, bob.state(iou.id).status)
    }

    private fun handedOver(
        debtor: Device,
        holder: String,
        metadata: Metadata,
        id: String = UUID.randomUUID().toString()
    ): Iou =
        handedOver(debtor.signer, holder, metadata, id)

    private fun handedOver(
        debtor: Signer,
        holder: String,
        metadata: Metadata,
        id: String = UUID.randomUUID().toString()
    ): Iou =
        Ledger.transfer(Ledger.issue(debtor, metadata, id = id), debtor, holder)
}
