package net.helcel.owu.ledger

import net.helcel.owu.crypto.Signer
import java.util.UUID

class LedgerException(message: String) : Exception(message)

/**
 * The only way blocks get made. Each operation checks what it needs from the
 * current state, signs, appends, and then has [Verifier] accept the result
 * before returning it, so a caller can never be handed a chain that a peer
 * would reject.
 */
object Ledger {

    fun now(): Long = System.currentTimeMillis() / 1000

    /**
     * Writes a promise. It starts held by the person who made it - an OwU
     * towards yourself - and becomes someone else's when it is traded away.
     * [creditor] names another holder only where a chain has to be built
     * whole, as in tests and fixtures.
     */
    fun issue(
        signer: Signer,
        metadata: Metadata,
        creditor: String = signer.publicKey,
        id: String = UUID.randomUUID().toString(),
        timestamp: Long = now(),
    ): Iou {
        val nb = metadata.notBefore
        val na = metadata.notAfter
        if (nb != null && na != null && nb > na) throw LedgerException("window closes before it opens")
        val unsigned = Block.Issue(
            timestamp = timestamp,
            debtor = signer.publicKey,
            creditor = creditor,
            metadataHash = metadata.hash(),
        )
        val genesis = unsigned.copy(signature = signer.sign(unsigned.signedBytes(id)))
        return accept(Iou(id, metadata, listOf(genesis)))
    }

    fun transfer(iou: Iou, signer: Signer, transferee: String, timestamp: Long = now()): Iou {
        val state = active(iou)
        if (state.holder != signer.publicKey) throw LedgerException("only the holder can transfer")
        if (transferee == signer.publicKey) throw LedgerException("cannot transfer to yourself")
        if (!iou.metadata.allowsTransfer(state, transferee)) throw LedgerException("this OwU can only go back to who wrote it")
        val unsigned = Block.Transfer(
            sequence = state.length,
            timestamp = timestamp,
            parentHash = state.headHash,
            transferor = signer.publicKey,
            transferee = transferee,
        )
        return append(iou, unsigned.copy(signature = signer.sign(unsigned.signedBytes(iou.id))))
    }

    /**
     * The debtor closes their own promise, which is what becomes of an OwU
     * handed back to the person who made it. Nothing can follow.
     */
    fun redeem(iou: Iou, signer: Signer, timestamp: Long = now()): Iou {
        val state = active(iou)
        if (state.debtor != signer.publicKey) throw LedgerException("only the debtor can close a promise")
        if (state.holder != signer.publicKey) throw LedgerException("the OwU has to be back with you first")
        val unsigned = Block.Redeemed(
            sequence = state.length,
            timestamp = timestamp,
            parentHash = state.headHash,
            debtor = signer.publicKey,
        )
        return append(iou, unsigned.copy(signature = signer.sign(unsigned.signedBytes(iou.id))))
    }

    /**
     * Step one of a swap: the holder of [mine] offers all of it for all of
     * [theirs], signing the left side. Either bundle may hold several OwUs
     * and they stand or fall together. [theirs] is the counterparty's side as
     * we last saw it.
     */
    fun proposeExchange(
        mine: List<Iou>,
        theirs: List<Iou>,
        signer: Signer,
        id: String = UUID.randomUUID().toString(),
        timestamp: Long = now(),
    ): ExchangeAgreement {
        if (mine.isEmpty() || theirs.isEmpty()) throw LedgerException("a swap needs something on both sides")
        val mineStates = mine.map { it to active(it) }
        val theirsStates = theirs.map { it to active(it) }
        val theirIds = theirs.map { it.id }.toSet()
        if (mine.any { it.id in theirIds }) throw LedgerException("cannot swap an OwU with itself")
        if (mineStates.any { it.second.holder != signer.publicKey }) throw LedgerException("only the holder can offer an OwU")
        if (theirsStates.any { it.second.holder == signer.publicKey }) throw LedgerException("you already hold the other OwU")
        val holder = signer.publicKey
        val theirHolder = theirsStates.first().second.holder
        if (theirsStates.any { it.second.holder != theirHolder }) throw LedgerException("their side is held by more than one person")
        if (mineStates.any { (iou, s) -> !iou.metadata.allowsTransfer(s, theirHolder) } ||
            theirsStates.any { (iou, s) -> !iou.metadata.allowsTransfer(s, holder) }
        ) throw LedgerException("a non-transferable OwU can only go back to who wrote it")
        val draft = ExchangeAgreement(
            id = id,
            timestamp = timestamp,
            left = ExchangeSide(holder, mineStates.map { ExchangeRef(it.first.id, it.second.headHash) }),
            right = ExchangeSide(theirHolder, theirsStates.map { ExchangeRef(it.first.id, it.second.headHash) }),
        )
        return draft.copy(left = draft.left.copy(signature = signer.sign(draft.signingBytes())))
    }

    /**
     * Step two: the counterparty, holding the right-hand side, countersigns.
     * [mine] is their copy of every OwU on it, each of which must still be at
     * the head the proposal was made against.
     */
    fun acceptExchange(proposal: ExchangeAgreement, mine: List<Iou>, signer: Signer): ExchangeAgreement {
        if (proposal.left.signature == null) throw LedgerException("proposal is not signed by the proposer")
        if (proposal.right.signature != null) throw LedgerException("proposal is already accepted")
        if (proposal.right.holder != signer.publicKey) throw LedgerException("proposal names a different holder")
        val byId = mine.associateBy { it.id }
        if (byId.size != proposal.right.ious.size || proposal.right.ious.any { it.iouId !in byId })
            throw LedgerException("proposal is not for these OwUs")
        proposal.right.ious.forEach { ref ->
            val state = active(byId.getValue(ref.iouId))
            if (state.holder != signer.publicKey) throw LedgerException("only the holder can accept")
            if (ref.headHash != state.headHash) throw LedgerException("OwU has changed since the proposal")
            if (!byId.getValue(ref.iouId).metadata.allowsTransfer(state, proposal.left.holder))
                throw LedgerException("a non-transferable OwU can only go back to who wrote it")
        }
        return proposal.copy(right = proposal.right.copy(signature = signer.sign(proposal.signingBytes())))
    }

    /** Step three, run once per OwU by whoever has the completed agreement. */
    fun applyExchange(iou: Iou, agreement: ExchangeAgreement): Iou {
        if (!agreement.complete) throw LedgerException("agreement is not signed by both sides")
        val side = agreement.side(iou.id) ?: throw LedgerException("agreement does not name this OwU")
        val state = active(iou)
        if (side.ref(iou.id)!!.headHash != state.headHash) throw LedgerException("OwU has changed since the agreement")
        val block = Block.Transfer(
            sequence = state.length,
            timestamp = agreement.timestamp,
            parentHash = state.headHash,
            transferor = side.holder,
            transferee = agreement.other(iou.id)!!.holder,
            agreement = agreement,
            signature = side.signature!!,
        )
        return append(iou, block)
    }

    private fun valid(iou: Iou): IouState = when (val v = Verifier.verify(iou)) {
        is Verdict.Valid -> v.state
        is Verdict.Invalid -> throw LedgerException("invalid chain at block ${v.sequence}: ${v.reason}")
    }

    private fun active(iou: Iou): IouState = valid(iou).also {
        if (it.status != Status.ACTIVE) throw LedgerException("OwU is ${it.status}")
    }

    private fun append(iou: Iou, block: Block): Iou = accept(iou.copy(chain = iou.chain + block))

    private fun accept(iou: Iou): Iou {
        valid(iou)
        return iou
    }
}
