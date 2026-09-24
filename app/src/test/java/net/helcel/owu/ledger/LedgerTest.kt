package net.helcel.owu.ledger

import net.helcel.owu.crypto.JvmSigner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LedgerTest {

    private val alice = JvmSigner()
    private val bob = JvmSigner()
    private val carol = JvmSigner()
    private val hug = Metadata(title = "1 Heavy Hug", templateId = "tmpl_hug_01")

    private fun valid(iou: Iou): IouState {
        val v = Verifier.verify(iou)
        assertIs<Verdict.Valid>(v, "expected valid, got $v")
        return v.state
    }

    private fun invalid(iou: Iou, reasonContains: String): Verdict.Invalid {
        val v = Verifier.verify(iou)
        assertIs<Verdict.Invalid>(v, "expected invalid ($reasonContains), got $v")
        assertTrue(v.reason.contains(reasonContains), "reason '${v.reason}' should mention '$reasonContains'")
        return v
    }

    private fun replaceHead(iou: Iou, block: Block) = iou.copy(chain = iou.chain.dropLast(1) + block)

    // --- issue -------------------------------------------------------------

    @Test
    fun `issue produces an active OwU held by the creditor`() {
        val iou = Ledger.issue(alice, creditor = bob.publicKey, metadata = hug)
        val s = valid(iou)
        assertEquals(alice.publicKey, s.debtor)
        assertEquals(bob.publicKey, s.holder)
        assertEquals(Status.ACTIVE, s.status)
        assertEquals(1, s.length)
    }

    @Test
    fun `an OwU issued to yourself is a blank promise you can trade away but not transfer to yourself`() {
        val blank = Ledger.issue(alice, creditor = alice.publicKey, metadata = hug)
        val s = valid(blank)
        assertEquals(alice.publicKey, s.debtor)
        assertEquals(alice.publicKey, s.holder)
        assertFailsWith<LedgerException> { Ledger.transfer(blank, alice, alice.publicKey) }
        val given = Ledger.transfer(blank, alice, bob.publicKey)
        assertEquals(bob.publicKey, valid(given).holder)
        // and it swaps like any other OwU
        val y = Ledger.issue(carol, creditor = bob.publicKey, metadata = Metadata("Y"))
        val a = Ledger.acceptExchange(Ledger.proposeExchange(listOf(blank), listOf(y), alice), listOf(y), bob)
        assertEquals(bob.publicKey, valid(Ledger.applyExchange(blank, a)).holder)
        assertEquals(alice.publicKey, valid(Ledger.applyExchange(y, a)).holder)
    }

    @Test
    fun `edited metadata breaks the genesis hash`() {
        val iou = Ledger.issue(alice, creditor = bob.publicKey, metadata = hug)
        invalid(iou.copy(metadata = hug.copy(title = "1 Car")), "metadata")
    }

    @Test
    fun `genesis cannot be replayed under another id`() {
        val iou = Ledger.issue(alice, creditor = bob.publicKey, metadata = hug)
        invalid(iou.copy(id = "another-id"), "signature")
    }

    @Test
    fun `genesis signed by someone other than the debtor is rejected`() {
        val iou = Ledger.issue(alice, creditor = bob.publicKey, metadata = hug)
        val forged = (iou.chain[0] as Block.Issue).let { g ->
            g.copy(debtor = carol.publicKey).let { it.copy(signature = alice.sign(it.signedBytes(iou.id))) }
        }
        invalid(iou.copy(chain = listOf(forged)), "signature")
    }

    // --- transfer ----------------------------------------------------------

    @Test
    fun `holder can transfer, then the new holder can transfer again`() {
        var iou = Ledger.issue(alice, creditor = bob.publicKey, metadata = hug)
        iou = Ledger.transfer(iou, bob, carol.publicKey)
        assertEquals(carol.publicKey, valid(iou).holder)
        iou = Ledger.transfer(iou, carol, alice.publicKey)
        assertEquals(alice.publicKey, valid(iou).holder)
        assertEquals(3, valid(iou).length)
    }

    @Test
    fun `non-holder cannot transfer`() {
        val iou = Ledger.issue(alice, creditor = bob.publicKey, metadata = hug)
        assertFailsWith<LedgerException> { Ledger.transfer(iou, carol, alice.publicKey) }
        assertFailsWith<LedgerException> { Ledger.transfer(iou, alice, carol.publicKey) }
    }

    @Test
    fun `forged transfer signed by the wrong key is rejected`() {
        val iou = Ledger.issue(alice, creditor = bob.publicKey, metadata = hug)
        val state = valid(iou)
        val unsigned =
            Block.Transfer(1, Ledger.now(), state.headHash, transferor = bob.publicKey, transferee = carol.publicKey)
        val forged = unsigned.copy(signature = carol.sign(unsigned.signedBytes(iou.id)))
        invalid(iou.copy(chain = iou.chain + forged), "signature")
    }

    @Test
    fun `transfer claiming a non-holder as transferor is rejected`() {
        val iou = Ledger.issue(alice, creditor = bob.publicKey, metadata = hug)
        val state = valid(iou)
        val unsigned =
            Block.Transfer(1, Ledger.now(), state.headHash, transferor = carol.publicKey, transferee = alice.publicKey)
        val block = unsigned.copy(signature = carol.sign(unsigned.signedBytes(iou.id)))
        invalid(iou.copy(chain = iou.chain + block), "not the holder")
    }

    @Test
    fun `tampering with a middle block breaks the chain`() {
        var iou = Ledger.issue(alice, creditor = bob.publicKey, metadata = hug)
        iou = Ledger.transfer(iou, bob, carol.publicKey)
        iou = Ledger.transfer(iou, carol, alice.publicKey)
        val t1 = iou.chain[1] as Block.Transfer
        val tampered = iou.copy(chain = listOf(iou.chain[0], t1.copy(timestamp = t1.timestamp + 1), iou.chain[2]))
        assertIs<Verdict.Invalid>(Verifier.verify(tampered))
    }

    @Test
    fun `dropping a block or reordering is rejected`() {
        var iou = Ledger.issue(alice, creditor = bob.publicKey, metadata = hug)
        iou = Ledger.transfer(iou, bob, carol.publicKey)
        iou = Ledger.transfer(iou, carol, alice.publicKey)
        invalid(iou.copy(chain = listOf(iou.chain[0], iou.chain[2])), "sequence")
        invalid(iou.copy(chain = listOf(iou.chain[0], iou.chain[2], iou.chain[1])), "sequence")
    }

    @Test
    fun `a shorter honest prefix is still valid`() {
        var iou = Ledger.issue(alice, creditor = bob.publicKey, metadata = hug)
        iou = Ledger.transfer(iou, bob, carol.publicKey)
        assertEquals(bob.publicKey, valid(iou.copy(chain = iou.chain.take(1))).holder)
    }

    // --- redeem ------------------------------------------------------------

    @Test
    fun `a promise handed home is closed by the one who made it, and nothing follows`() {
        var iou = Ledger.issue(alice, creditor = bob.publicKey, metadata = hug)
        // While somebody else holds it, nobody can close it - not even alice.
        assertFailsWith<LedgerException> { Ledger.redeem(iou, alice) }
        assertFailsWith<LedgerException> { Ledger.redeem(iou, bob) }

        iou = Ledger.transfer(iou, bob, alice.publicKey)
        assertEquals(Status.ACTIVE, valid(iou).status, "home, but still a promise")
        assertFailsWith<LedgerException> { Ledger.redeem(iou, bob) }
        assertFailsWith<LedgerException> { Ledger.redeem(iou, carol) }

        iou = Ledger.redeem(iou, alice)
        assertEquals(Status.REDEEMED, valid(iou).status)
        assertFailsWith<LedgerException> { Ledger.transfer(iou, alice, carol.publicKey) }
        assertFailsWith<LedgerException> { Ledger.redeem(iou, alice) }
        assertEquals(iou, IouJson.decode(IouJson.encode(iou)))
    }

    @Test
    fun `an OwU closed while somebody else held it is rejected`() {
        val iou = Ledger.issue(alice, creditor = bob.publicKey, metadata = hug)
        val state = valid(iou)
        val unsigned = Block.Redeemed(1, Ledger.now(), state.headHash, debtor = alice.publicKey)
        val block = unsigned.copy(signature = alice.sign(unsigned.signedBytes(iou.id)))
        invalid(iou.copy(chain = iou.chain + block), "while somebody else held it")
    }

    @Test
    fun `block appended after redemption is rejected`() {
        var iou = Ledger.issue(alice, creditor = bob.publicKey, metadata = hug)
        iou = Ledger.transfer(iou, bob, alice.publicKey)
        iou = Ledger.redeem(iou, alice)
        val state = valid(iou)
        val unsigned =
            Block.Transfer(3, Ledger.now(), state.headHash, transferor = bob.publicKey, transferee = carol.publicKey)
        val block = unsigned.copy(signature = bob.sign(unsigned.signedBytes(iou.id)))
        invalid(iou.copy(chain = iou.chain + block), "after redemption")
    }

    // --- exchange ----------------------------------------------------------

    private fun twoIous(): Pair<Iou, Iou> {
        val x = Ledger.issue(
            alice,
            creditor = bob.publicKey,
            metadata = hug
        )                       // bob holds X (alice owes)
        val y = Ledger.issue(
            carol,
            creditor = alice.publicKey,
            metadata = Metadata("1 Coffee")
        )    // alice holds Y (carol owes)
        return x to y
    }

    @Test
    fun `full exchange swaps holders on both chains`() {
        val (x, y) = twoIous()
        val proposal = Ledger.proposeExchange(mine = listOf(x), theirs = listOf(y), signer = bob)
        assertTrue(!proposal.complete)
        val agreement = Ledger.acceptExchange(proposal, mine = listOf(y), signer = alice)
        assertTrue(agreement.complete)

        val x2 = Ledger.applyExchange(x, agreement)
        val y2 = Ledger.applyExchange(y, agreement)
        assertEquals(alice.publicKey, valid(x2).holder)
        assertEquals(bob.publicKey, valid(y2).holder)
        assertEquals(alice.publicKey, valid(x2).debtor)
        assertEquals(carol.publicKey, valid(y2).debtor)

        // Either party can apply both once both have signed.
        assertEquals(valid(x2), valid(Ledger.applyExchange(x, agreement)))
    }

    @Test
    fun `an unaccepted proposal cannot be applied`() {
        val (x, y) = twoIous()
        val proposal = Ledger.proposeExchange(listOf(x), listOf(y), bob)
        assertFailsWith<LedgerException> { Ledger.applyExchange(x, proposal) }
        assertFailsWith<LedgerException> { Ledger.applyExchange(y, proposal) }
    }

    @Test
    fun `only the counterparty holder can accept`() {
        val (x, y) = twoIous()
        val proposal = Ledger.proposeExchange(listOf(x), listOf(y), bob)
        assertFailsWith<LedgerException> { Ledger.acceptExchange(proposal, listOf(y), carol) }
        assertFailsWith<LedgerException> { Ledger.acceptExchange(proposal, listOf(y), bob) }
        assertFailsWith<LedgerException> { Ledger.acceptExchange(proposal, listOf(x), alice) }
    }

    @Test
    fun `agreement dies if either OwU moves first`() {
        val (x, y) = twoIous()
        val agreement = Ledger.acceptExchange(Ledger.proposeExchange(listOf(x), listOf(y), bob), listOf(y), alice)
        val yMoved = Ledger.transfer(Ledger.transfer(y, alice, carol.publicKey), carol, alice.publicKey)
        assertEquals(alice.publicKey, valid(yMoved).holder) // same holder, different head
        assertFailsWith<LedgerException> { Ledger.applyExchange(yMoved, agreement) }
        // and a hand-built block on the moved chain is rejected by the verifier
        val state = valid(yMoved)
        val forged = Block.Transfer(
            sequence = state.length,
            timestamp = agreement.timestamp,
            parentHash = state.headHash,
            transferor = agreement.side(y.id)!!.holder,
            transferee = agreement.other(y.id)!!.holder,
            agreement = agreement,
            signature = agreement.side(y.id)!!.signature!!,
        )
        invalid(yMoved.copy(chain = yMoved.chain + forged), "different head")
    }

    @Test
    fun `exchange block with a forged counterparty signature is rejected`() {
        val (x, y) = twoIous()
        val proposal = Ledger.proposeExchange(listOf(x), listOf(y), bob)
        // carol pretends to be alice accepting
        val fake = proposal.copy(right = proposal.right.copy(signature = carol.sign(proposal.signingBytes())))
        assertTrue(fake.complete)
        val state = valid(x)
        val block = Block.Transfer(
            sequence = state.length,
            timestamp = fake.timestamp,
            parentHash = state.headHash,
            transferor = fake.side(x.id)!!.holder,
            transferee = fake.other(x.id)!!.holder,
            agreement = fake,
            signature = fake.side(x.id)!!.signature!!,
        )
        invalid(x.copy(chain = x.chain + block), "counterparty signature")
    }

    /**
     * A TRANSFER says who it moves the OwU between, and a swap's agreement
     * says the same. They have to agree, or the block moves it somewhere the
     * signed deal never mentioned.
     */
    @Test
    fun `a swap cannot send the OwU anywhere but where its agreement says`() {
        val (x, y) = twoIous()
        val agreement = Ledger.acceptExchange(Ledger.proposeExchange(listOf(x), listOf(y), bob), listOf(y), alice)
        val state = valid(x)
        val block = Block.Transfer(
            sequence = state.length,
            timestamp = agreement.timestamp,
            parentHash = state.headHash,
            transferor = agreement.side(x.id)!!.holder,
            transferee = carol.publicKey,
            agreement = agreement,
            signature = agreement.side(x.id)!!.signature!!,
        )
        invalid(x.copy(chain = x.chain + block), "somebody else")
    }

    @Test
    fun `exchange block must carry this side's own agreement signature`() {
        val (x, y) = twoIous()
        val agreement = Ledger.acceptExchange(Ledger.proposeExchange(listOf(x), listOf(y), bob), listOf(y), alice)
        val state = valid(x)
        val block = Block.Transfer(
            sequence = state.length,
            timestamp = agreement.timestamp,
            parentHash = state.headHash,
            transferor = agreement.side(x.id)!!.holder,
            transferee = agreement.other(x.id)!!.holder,
            agreement = agreement,
            // The other side's signature, not this side's.
            signature = agreement.other(x.id)!!.signature!!,
        )
        invalid(x.copy(chain = x.chain + block), "signature")
    }

    @Test
    fun `cannot propose a swap for an OwU you do not hold, or against one you already hold`() {
        val (x, y) = twoIous()
        assertFailsWith<LedgerException> { Ledger.proposeExchange(listOf(x), listOf(y), alice) }
        val z = Ledger.issue(carol, creditor = bob.publicKey, metadata = Metadata("1 Beer"))
        assertFailsWith<LedgerException> { Ledger.proposeExchange(listOf(x), listOf(z), bob) }
    }

    // --- serialization -----------------------------------------------------

    @Test
    fun `json round trip preserves validity and equality`() {
        val (x, y) = twoIous()
        val agreement = Ledger.acceptExchange(Ledger.proposeExchange(listOf(x), listOf(y), bob), listOf(y), alice)
        var iou = Ledger.applyExchange(x, agreement)
        iou = Ledger.transfer(iou, alice, carol.publicKey)
        iou = Ledger.transfer(iou, carol, alice.publicKey)
        iou = Ledger.redeem(iou, alice)

        val text = IouJson.encode(iou)
        val back = IouJson.decode(text)
        assertEquals(iou, back)
        assertEquals(valid(iou), valid(back))
        assertTrue(text.contains("\"action\":\"ISSUE\""))
        assertTrue(text.contains("\"action\":\"TRANSFER\""))
        // A swap is a TRANSFER carrying the agreement; a plain one carries none.
        assertTrue(text.contains("\"agreement\""))
        assertTrue(text.contains("\"parent_hash\""))

        val a = IouJson.decodeAgreement(IouJson.encode(agreement))
        assertEquals(agreement, a)
    }

    @Test
    fun `redemption window is signed, checked for order, and gated`() {
        val meta = hug.copy(notBefore = 1_800_000_000L, notAfter = 1_800_100_000L)
        val iou = Ledger.issue(alice, creditor = bob.publicKey, metadata = meta)
        valid(iou)
        invalid(iou.copy(metadata = meta.copy(notAfter = 1_900_000_000L)), "metadata")
        assertEquals(iou, IouJson.decode(IouJson.encode(iou)))

        assertFailsWith<LedgerException> {
            Ledger.issue(
                alice,
                creditor = bob.publicKey,
                metadata = hug.copy(notBefore = 10, notAfter = 5)
            )
        }

        assertEquals(TimeGate.NotYet(1_800_000_000L), meta.timeGate(now = 1_799_999_999L))
        assertEquals(TimeGate.Open, meta.timeGate(now = 1_800_000_000L))
        assertEquals(TimeGate.Open, meta.timeGate(now = 1_800_100_000L))
        assertEquals(TimeGate.Expired(1_800_100_000L), meta.timeGate(now = 1_800_100_001L))
        assertEquals(TimeGate.Open, hug.timeGate(now = 0))
        assertEquals(TimeGate.Open, hug.copy(notAfter = 100).timeGate(now = 50))
        assertEquals(TimeGate.Expired(100), hug.copy(notAfter = 100).timeGate(now = 150))
        // The window is not a protocol rule: handing it back outside the
        // window still verifies, and so does closing it.
        val home = Ledger.transfer(iou, bob, alice.publicKey, timestamp = 1_900_000_000L)
        valid(Ledger.redeem(home, alice, timestamp = 1_900_000_000L))
    }

    @Test
    fun `geoloc is part of the signed metadata`() {
        val meta = hug.copy(geoloc = GeoLoc.of(46.5197, 6.6323, radiusM = 200, label = "Lausanne"))
        val iou = Ledger.issue(alice, creditor = bob.publicKey, metadata = meta)
        valid(iou)
        val moved = iou.copy(metadata = meta.copy(geoloc = meta.geoloc!!.copy(radiusM = 5000)))
        invalid(moved, "metadata")
        assertEquals(iou, IouJson.decode(IouJson.encode(iou)))
        assertEquals(46.5197, iou.metadata.geoloc!!.latitude, 1e-6)
    }
}
