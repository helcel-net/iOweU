package net.helcel.owu.ledger

import net.helcel.owu.crypto.Hash
import net.helcel.owu.crypto.Keys

sealed class Verdict {
    data class Valid(val state: IouState) : Verdict()
    data class Invalid(val sequence: Int, val reason: String) : Verdict()

    val stateOrNull: IouState? get() = (this as? Valid)?.state
}

/**
 * Decides whether a received chain is what it claims to be. Every rule that
 * makes a chain acceptable lives here and nowhere else; the builders in
 * [Ledger] run their output through it rather than trusting themselves.
 */
object Verifier {

    private class Rejected(val sequence: Int, val reason: String) : Exception(reason)

    fun verify(iou: Iou): Verdict = try {
        Verdict.Valid(walk(iou))
    } catch (e: Rejected) {
        Verdict.Invalid(e.sequence, e.reason)
    }

    private fun walk(iou: Iou): IouState {
        val chain = iou.chain
        if (chain.isEmpty()) throw Rejected(0, "empty chain")

        val genesis = chain[0] as? Block.Issue ?: throw Rejected(0, "first block is not an ISSUE")
        if (genesis.sequence != 0) throw Rejected(0, "genesis sequence is not 0")
        if (genesis.parentHash != Hash.ZERO) throw Rejected(0, "genesis has a parent")
        if (genesis.metadataHash != iou.metadata.hash()) throw Rejected(0, "metadata does not match its hash")
        checkSignature(iou.id, genesis)

        var state = IouState(
            debtor = genesis.debtor,
            holder = genesis.creditor,
            status = Status.ACTIVE,
            length = 1,
            headHash = genesis.hash(iou.id),
        )

        for (n in 1 until chain.size) {
            val block = chain[n]
            if (block.sequence != n) throw Rejected(n, "sequence is ${block.sequence}, expected $n")
            if (block.parentHash != state.headHash) throw Rejected(n, "parent hash does not match previous block")
            if (state.status == Status.REDEEMED) throw Rejected(n, "block after redemption")
            checkSignature(iou.id, block)

            state = when (block) {
                is Block.Issue -> throw Rejected(n, "a second ISSUE")
                is Block.Transfer -> transfer(n, iou, block, state)
                is Block.Redeemed -> redeemed(n, block, state)
            }.copy(length = n + 1, headHash = block.hash(iou.id))
        }
        return state
    }

    private fun checkSignature(iouId: String, block: Block) {
        val signer = block.signer(iouId) ?: throw Rejected(block.sequence, "block names no signer for this OwU")
        if (!Keys.verify(signer, block.signedBytes(iouId), block.signature))
            throw Rejected(block.sequence, "signature does not verify")
    }

    /**
     * A hand-over. The three rules above the agreement hold whether it is a
     * gift or half a swap; the rest bind this block to the other chain's.
     */
    private fun transfer(n: Int, iou: Iou, b: Block.Transfer, s: IouState): IouState {
        val iouId = iou.id
        if (s.status != Status.ACTIVE) throw Rejected(n, "transfer of an OwU that is ${s.status}")
        if (b.transferor != s.holder) throw Rejected(n, "transferor is not the holder")
        if (b.transferee == b.transferor) throw Rejected(n, "transfer to self")
        if (!iou.metadata.allowsTransfer(s, b.transferee)) throw Rejected(n, "non-transferable OwU passed on")
        val a = b.agreement ?: return s.copy(holder = b.transferee)

        val mine = a.side(iouId) ?: throw Rejected(n, "agreement does not name this OwU")
        val theirs = a.other(iouId)!!
        if (theirs.holds(iouId)) throw Rejected(n, "agreement swaps an OwU with itself")
        // The block says who it moves between and the agreement says the same,
        // or one of the two is lying about the deal this signature covers.
        if (mine.holder != b.transferor) throw Rejected(n, "agreement is not the transferor's side")
        if (theirs.holder != b.transferee) throw Rejected(n, "agreement sends it to somebody else")
        if (theirs.ious.isEmpty()) throw Rejected(n, "exchange for nothing")
        // Every OwU in the bundle is pinned; this one has to be at the head it
        // was signed against, or the deal both sides agreed to has changed.
        if (mine.ref(iouId)!!.headHash != b.parentHash) throw Rejected(
            n,
            "agreement was made against a different head"
        )
        if (b.timestamp != a.timestamp) throw Rejected(n, "block timestamp differs from agreement")
        if (!a.complete) throw Rejected(n, "agreement is not signed by both sides")
        if (b.signature != mine.signature) throw Rejected(n, "block signature is not this side's agreement signature")
        // Ours was checked in checkSignature; the counterparty's consent is
        // what makes the swap binding, so it is checked here.
        if (!Keys.verify(theirs.holder, a.signingBytes(), theirs.signature!!))
            throw Rejected(n, "counterparty signature does not verify")
        return s.copy(holder = b.transferee)
    }

    /**
     * A promise is closed by the one who made it, and only once it has come
     * back to them - which is what redeeming is: the OwU is handed home and
     * its maker voids it. There is no state in between.
     */
    private fun redeemed(n: Int, b: Block.Redeemed, s: IouState): IouState {
        if (s.status != Status.ACTIVE) throw Rejected(n, "redeeming an OwU that is ${s.status}")
        if (b.debtor != s.debtor) throw Rejected(n, "redeemed by someone who is not the debtor")
        if (s.holder != s.debtor) throw Rejected(n, "redeemed while somebody else held it")
        return s.copy(status = Status.REDEEMED)
    }
}
