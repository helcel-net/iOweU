package net.helcel.owu.peer

import net.helcel.owu.crypto.Canonical
import net.helcel.owu.crypto.Hash
import net.helcel.owu.crypto.Keys
import net.helcel.owu.crypto.Signer
import net.helcel.owu.helper.IdentityCard
import net.helcel.owu.ledger.Block
import net.helcel.owu.ledger.ExchangeAgreement
import net.helcel.owu.ledger.Iou
import net.helcel.owu.ledger.IouState
import net.helcel.owu.ledger.Ledger
import net.helcel.owu.ledger.LedgerException
import net.helcel.owu.ledger.Metadata
import net.helcel.owu.ledger.Status
import net.helcel.owu.ledger.Verdict
import net.helcel.owu.ledger.Verifier
import net.helcel.owu.store.IouStore
import net.helcel.owu.store.Merge
import java.security.SecureRandom

/**
 * One side of a session, knowing nothing of Bluetooth. Feed it messages and
 * user actions; it returns what to send and what happened. Run one thread at
 * a time by the transport.
 *
 * After HELLO/AUTH the peer is proven. Then: a **table**, each side putting
 * down an OwU or nothing and both accepting, or a **redemption**, a holder
 * asking and the debtor honouring. Giving, issuing and swapping are the table.
 */
class PeerEngine(
    private val store: IouStore,
    private val signer: Signer,
    private val myName: String,
) {
    sealed class Event {
        data class PeerIdentified(val card: IdentityCard) : Event()

        /** The peer started over (new HELLO); anything pending with them is void. */
        object Restarted : Event()

        /** The table changed: what is on it, and who has accepted it as it stands. */
        data class Tabled(val table: Table) : Event()

        /** Both accepted and it happened: what left, what arrived, either possibly nothing. */
        data class Done(val gave: List<Iou> = emptyList(), val got: List<Iou> = emptyList()) : Event()

        /** A promise came home and was closed: mine to file, or theirs, now spent. */
        data class Redeemed(val iou: Iou) : Event()

        /** They have opened a table with us and would like us at it. */
        data class Invited(val card: IdentityCard) : Event()

        /** They said no, to being asked to the table or to what was on it. */
        data class Declined(val card: IdentityCard, val to: PeerMessage.Asked) : Event()
        data class Failed(val reason: String) : Event()
    }

    data class Step(val send: List<PeerMessage> = emptyList(), val events: List<Event> = emptyList())

    /** The table as this side sees it. Each side is a bundle: five beers and
     *  two hugs are one deal, accepted once, moving together or not at all. */
    data class Table(
        val mine: List<Iou> = emptyList(),
        val theirs: List<Iou> = emptyList(),
        /** I have said yes to the table as it stands. */
        val accepted: Boolean = false,
        /** They have said yes to the table as it stands. */
        val theyAccepted: Boolean = false,
        /** Their OwU contradicts the copy I hold: signed over twice. Nothing
         *  may be accepted against it. */
        val conflict: Boolean = false,
    ) {
        val empty: Boolean get() = mine.isEmpty() && theirs.isEmpty()
    }

    val me: String get() = signer.publicKey
    private var myNonce: String = freshNonce()

    private fun freshNonce() = ByteArray(16).also { SecureRandom().nextBytes(it) }.let { Hash.hex(it) }

    /** The peer, once they have proven they hold their key. */
    var peer: IdentityCard? = null
        private set
    private var peerHello: PeerMessage.Hello? = null
    private var earlyAuth: PeerMessage.Auth? = null

    private var myOffer: List<Iou> = emptyList()
    private var theirOffer: List<Iou> = emptyList()

    /** The deal each side has accepted, if any; stale as soon as the table changes. */
    private var myAccepted: String? = null
    private var theirAccepted: String? = null

    /** OwUs minted from templates for this table, until they leave or are dropped. */
    private val minted = mutableSetOf<String>()

    /** Their OwU contradicts a copy we hold; set when it lands on the table. */
    private var theirConflict = false

    /** Their half-signed swap, held until I accept too. */
    private var theirProposal: ExchangeAgreement? = null

    /** My own half-signed swap for this table, so it is signed only once. */
    private var myProposal: ExchangeAgreement? = null


    fun start(): Step = Step(send = listOf(myHello()))

    fun onMessage(m: PeerMessage): Step = try {
        when (m) {
            is PeerMessage.Hello -> hello(m)
            is PeerMessage.Auth -> auth(m)
            else -> {
                if (peer == null) unknown()
                else when (m) {
                    is PeerMessage.Invite -> invited(m)
                    is PeerMessage.Decline -> declined(m)
                    is PeerMessage.Table -> tabled(m)
                    is PeerMessage.Accept -> accepted(m)
                    is PeerMessage.Give -> given(m)
                    is PeerMessage.Redeemed -> redeemed(m)
                    is PeerMessage.Hello, is PeerMessage.Auth -> throw IllegalStateException("unreachable")
                }
            }
        }
    } catch (e: Exception) {
        Step(events = listOf(Event.Failed(e.message ?: e.toString())))
    }

    // --- handshake ---------------------------------------------------------

    /** Every HELLO is answered with AUTH, so a peer that missed our answer can
     *  ask again. Only a *new* nonce from a verified peer means they restarted. */
    private fun hello(m: PeerMessage.Hello): Step {
        if (Keys.decode(m.identity.key) == null) throw IllegalStateException("peer key unreadable")
        if (m.identity.key == me) throw IllegalStateException("peer is this device")
        val previous = peerHello
        if (previous != null && previous.identity.key != m.identity.key) throw IllegalStateException("HELLO from a different key")
        val auth = PeerMessage.Auth(signer.sign(authBytes(m.nonce, me)))
        if (peer != null && previous != null && previous.nonce != m.nonce) {
            reset()
            peerHello = m
            return Step(send = listOf(myHello(), auth), events = listOf(Event.Restarted))
        }
        peerHello = m
        // Their AUTH may have overtaken their HELLO; now it can be checked.
        val early = earlyAuth?.also { earlyAuth = null }
        if (early != null && peer == null && verifies(m, early)) {
            peer = m.identity
            store.putMet(m.identity.key, m.identity.name)
            return Step(send = listOf(auth), events = listOf(Event.PeerIdentified(m.identity)))
        }
        return Step(send = listOf(auth))
    }

    private fun verifies(h: PeerMessage.Hello, a: PeerMessage.Auth): Boolean =
        Keys.verify(h.identity.key, authBytes(myNonce, h.identity.key), a.signature)

    /** Our HELLO again, for a peer that has not answered; same nonce, so nothing restarts. */
    fun reintroduce(): Step = Step(send = listOf(myHello()))

    /**
     * They talk as though we knew them and we do not: their session outlived
     * ours, or our radio restarted under them. Dropping it leaves them waiting
     * for ever - the one deadlock this protocol had. So say who we are: an
     * unseen nonce restarts them, and they can try again.
     */
    private fun unknown(): Step = Step(send = listOf(myHello()))

    private fun myHello() = PeerMessage.Hello(IdentityCard(name = myName, key = me), myNonce)

    private fun reset() {
        abandon()
        peer = null
        peerHello = null
        earlyAuth = null
        myNonce = freshNonce()
        clearTable()
    }

    private fun auth(m: PeerMessage.Auth): Step {
        val h = peerHello
        if (h == null) {
            // Nothing to check it against yet; keep it for when their HELLO arrives.
            earlyAuth = m
            return Step()
        }
        if (!verifies(h, m)) throw IllegalStateException("peer failed to prove its key")
        // A repeat of a good AUTH (they answered our HELLO twice) changes nothing.
        if (peer != null) return Step()
        peer = h.identity
        // The key is proven; remember the name they claim so screens read as
        // people rather than hashes.
        store.putMet(h.identity.key, h.identity.name)
        return Step(events = listOf(Event.PeerIdentified(h.identity)))
    }

    private val peerKey: String get() = peer?.key ?: throw IllegalStateException("no peer")

    // --- the table ---------------------------------------------------------

    fun table(): Table = Table(
        mine = myOffer,
        theirs = theirOffer,
        accepted = myAccepted != null && myAccepted == deal(),
        theyAccepted = theirAccepted != null && theirAccepted == deal(),
        conflict = theirConflict,
    )

    /**
     * My side of the table: [held] as they are, plus one fresh OwU per [mint]
     * entry - five beers is the beer template five times. An empty call takes
     * my side off. Changing the table withdraws both yeses, since nobody is
     * held to a yes given to something else.
     */
    fun put(held: List<Iou> = emptyList(), mint: List<Metadata> = emptyList()): Step {
        held.forEach {
            if (active(it).holder != me) throw LedgerException("you do not hold that OwU")
        }
        // Minted here, not by the caller, so a bundle that never leaves takes
        // its fresh ious with it.
        val fresh = mint.map { Ledger.issue(signer, it).also { iou -> store.put(iou) } }
        val offer = held + fresh
        discardMinted(keep = offer.map { it.id }.toSet())
        minted += fresh.map { it.id }
        myOffer = offer
        forget()
        return Step(send = listOf(PeerMessage.Table(me, offer)), events = listOf(Event.Tabled(table())))
    }

    /** Mints one promise from a template onto the table alone: writing one mid-trade. */
    fun putNew(metadata: Metadata): Step = put(mint = listOf(metadata))

    /** Nothing came of the table: forget anything minted for it. */
    fun abandon() {
        discardMinted(keep = emptySet())
    }

    /** Drops ious minted here and not in [keep], but only while still a bare
     *  genesis in my hands. Once one has moved it is somebody's promise. */
    private fun discardMinted(keep: Set<String>) {
        val going = minted - keep
        minted.retainAll(keep)
        going.forEach { id ->
            val iou = store.ious.value[id] ?: return@forEach
            val state = Verifier.verify(iou).stateOrNull ?: return@forEach
            if (iou.chain.size == 1 && state.holder == me && state.debtor == me) store.remove(id)
        }
    }

    /**
     * Their side of the table, from a TABLE or riding on their acceptance.
     *
     * Checked against what we hold *before* anyone agrees: the one moment a
     * double-spend is caught without the debtor in the room. Contradicting
     * histories are one such; so is an OwU offered at a head we know has moved
     * on. One bad OwU taints the bundle, since it moves as one.
     */
    private fun adoptTheirs(ious: List<Iou>) {
        ious.forEach {
            if (active(it).holder != peerKey) throw IllegalStateException("they offer an OwU they do not hold")
        }
        if (ious.map { it.id }.toSet().size != ious.size) throw IllegalStateException("the same OwU twice")
        theirOffer = ious
        theirConflict = ious.any { iou ->
            when (store.compare(iou)) {
                is Merge.Fork -> true
                Merge.Unchanged -> iou.chain.size < (store.ious.value[iou.id]?.chain?.size ?: 0)
                else -> false
            }
        }
    }

    /** Sent on opening a table. Carries and changes nothing; being asked is all of it. */
    fun invite(): Step = Step(send = listOf(PeerMessage.Invite(me)))

    private fun invited(m: PeerMessage.Invite): Step {
        val card = peer ?: throw IllegalStateException("no peer")
        if (m.key != card.key) throw IllegalStateException("that invitation is signed by somebody else")
        return Step(events = listOf(Event.Invited(card)))
    }

    /** Turn them down. Refusing the table clears it both sides, and a minted
     *  OwU that never left goes with it. */
    fun decline(to: PeerMessage.Asked): Step {
        val send = listOf(PeerMessage.Decline(me, to))
        if (to != PeerMessage.Asked.TABLE) return Step(send = send)
        abandon()
        clearTable()
        return Step(send = send, events = listOf(Event.Tabled(table())))
    }

    private fun declined(m: PeerMessage.Decline): Step {
        val card = peer ?: throw IllegalStateException("no peer")
        if (m.key != card.key) throw IllegalStateException("that refusal is signed by somebody else")
        val said = Event.Declined(card, m.to)
        if (m.to != PeerMessage.Asked.TABLE) return Step(events = listOf(said))
        abandon()
        clearTable()
        return Step(events = listOf(said, Event.Tabled(table())))
    }

    private fun tabled(m: PeerMessage.Table): Step {
        adoptTheirs(m.ious)
        forget()
        return Step(events = listOf(Event.Tabled(table())))
    }

    /** Yes to the table as it stands. Two yeses to the same table and it
     *  happens: a swap if both brought something, a transfer if one did. */
    fun accept(): Step {
        val deal = deal() ?: throw LedgerException("there is nothing on the table")
        if (theirConflict) throw LedgerException("that OwU contradicts the copy you hold; it has been signed over twice")
        myAccepted = deal
        val step = advance()
        if (step.send.isNotEmpty()) return step
        // My side rides along with my yes, so losing the TABLE cannot strand it.
        return Step(
            send = listOf(PeerMessage.Accept(me, deal, myOffer)),
            events = listOf(Event.Tabled(table())),
        )
    }

    private fun accepted(m: PeerMessage.Accept): Step {
        // An acceptance carries its own side, so it stands alone. The TABLE
        // before it may never have arrived - a big fragmented message where
        // this one is small - which used to leave the table empty and the
        // acceptance refused as "they accepted with nothing on the table".
        if (m.ious.isNotEmpty() && m.ious.map { it.id }.toSet() != theirOffer.map { it.id }.toSet()) {
            adoptTheirs(m.ious)
        }
        val deal = deal() ?: throw IllegalStateException("they accepted with nothing on the table")
        if (m.deal != deal) throw IllegalStateException("the table changed while they were accepting")
        m.agreement?.let { a ->
            if (a.complete) return complete(a, m.ious)
            checkProposal(a)
            theirProposal = a
        }
        theirAccepted = deal
        val step = advance()
        if (step.send.isNotEmpty() || step.events.isNotEmpty()) return step
        return Step(events = listOf(Event.Tabled(table())))
    }

    /**
     * What my yes now allows. For a swap it carries a signature: the side
     * holding the lowest OwU id half-signs over both bundles, the other
     * countersigns, and countersigning is what makes it happen.
     */
    private fun advance(): Step {
        val deal = deal() ?: return Step()
        if (myAccepted != deal) return Step()
        val mine = myOffer
        val theirs = theirOffer
        return when {
            mine.isNotEmpty() && theirs.isNotEmpty() -> {
                val proposal = theirProposal
                when {
                    proposal != null -> {
                        val agreement = Ledger.acceptExchange(proposal, mine, signer)
                        val myNew = mine.map { Ledger.applyExchange(it, agreement) }
                        val theirNew = theirs.map { Ledger.applyExchange(it, agreement) }
                        (myNew + theirNew).forEach { store.put(it) }
                        clearTable()
                        val done = Event.Done(gave = myNew, got = theirNew)
                        val accept = PeerMessage.Accept(me, deal, mine, agreement)
                        val home = closeIfHome(theirNew)
                        Step(send = listOf(accept) + home.send, events = listOf(done) + home.events)
                    }
                    // Mine signs first, once. The lowest id decides, so both agree who.
                    mine.minOf { it.id } < theirs.minOf { it.id } && myProposal == null -> {
                        val proposed = Ledger.proposeExchange(mine, theirs, signer)
                        myProposal = proposed
                        Step(send = listOf(PeerMessage.Accept(me, deal, mine, proposed)))
                    }
                    // Their side signs first, or mine already did; wait for it.
                    else -> Step()
                }
            }
            // Only my side has anything: once they have said yes too, it is theirs.
            mine.isNotEmpty() && theirAccepted == deal -> {
                val given =
                    mine.map { Ledger.transfer(store.ious.value[it.id] ?: it, signer, peerKey).also(store::put) }
                clearTable()
                Step(send = listOf(PeerMessage.Give(given)), events = listOf(Event.Done(gave = given)))
            }
            // Only theirs: they sign it over, so wait for it to arrive.
            else -> Step()
        }
    }

    /** Their acceptance carried the agreement with both signatures on it: apply it here too. */
    private fun complete(a: ExchangeAgreement, theirs: List<Iou>): Step {
        val mineSide = a.side(myOffer.firstOrNull()?.id ?: "")
            ?: throw IllegalStateException("agreement is not about what you put down")
        if (mineSide.holder != me) throw IllegalStateException("agreement does not name you as the holder")
        val signature = mineSide.signature ?: throw IllegalStateException("agreement is unsigned on your side")
        if (!Keys.verify(
                me,
                a.signingBytes(),
                signature
            )
        ) throw IllegalStateException("acceptance of a proposal you did not make")
        if (mineSide.ious.map { it.iouId }.toSet() != myOffer.map { it.id }.toSet())
            throw IllegalStateException("agreement is not about what you put down")
        val their = theirs.ifEmpty { theirOffer }
        val theirSide =
            a.other(mineSide.ious.first().iouId) ?: throw IllegalStateException("agreement has no other side")
        val byId = their.associateBy { it.id }
        if (byId.size != theirSide.ious.size || theirSide.ious.any { ref ->
                byId[ref.iouId]?.headHash() != ref.headHash
            }) throw IllegalStateException("acceptance does not match the OwUs sent with it")
        val myNew = myOffer.map {
            Ledger.applyExchange(
                store.ious.value[it.id] ?: throw IllegalStateException("your OwU is gone"), a
            )
        }
        val theirNew = their.map { Ledger.applyExchange(it, a) }
        (myNew + theirNew).forEach { store.put(it) }
        clearTable()
        val done = Event.Done(gave = myNew, got = theirNew)
        // What came back may be promises of mine: the swap redeemed them.
        val home = closeIfHome(theirNew)
        return Step(send = home.send, events = listOf(done) + home.events)
    }

    /** Their half-signed swap: it must be about what is on the table, and signed by them. */
    private fun checkProposal(a: ExchangeAgreement) {
        if (theirOffer.isEmpty()) throw IllegalStateException("a swap with nothing on their side")
        if (myOffer.isEmpty()) throw IllegalStateException("a swap with nothing on your side")
        if (a.left.holder != peerKey) throw IllegalStateException("proposal is not theirs")
        if (a.right.holder != me) throw IllegalStateException("proposal is not addressed to you")
        val sig = a.left.signature ?: throw IllegalStateException("proposal unsigned")
        if (!Keys.verify(peerKey, a.signingBytes(), sig)) throw IllegalStateException("proposal signature invalid")
        if (!sameBundle(
                a.left,
                theirOffer
            ) { it }
        ) throw IllegalStateException("proposal is not about what they put down")
        if (!sameBundle(a.right, myOffer) {
                store.ious.value[it.id] ?: throw IllegalStateException("your OwU is gone")
            })
            throw IllegalStateException("proposal is not about what you put down")
    }

    /** The side names exactly these ious, each at the head we have for it. */
    private fun sameBundle(side: net.helcel.owu.ledger.ExchangeSide, ious: List<Iou>, current: (Iou) -> Iou): Boolean {
        if (side.ious.size != ious.size) return false
        return ious.all { iou -> side.ref(iou.id)?.headHash == current(iou).headHash() }
    }

    private fun given(m: PeerMessage.Give): Step {
        if (m.ious.isEmpty()) throw IllegalStateException("a gift of nothing")
        val expected = theirOffer.map { it.id }.toSet()
        m.ious.forEach { iou ->
            val s = active(iou)
            val head = iou.head as? Block.Transfer ?: throw IllegalStateException("a gift with no transfer on it")
            if (head.transferor != peerKey || head.transferee != me || s.holder != me)
                throw IllegalStateException("that gift is not from them to you")
            if (expected.isNotEmpty() && iou.id !in expected) throw IllegalStateException("a gift of something else")
            keep(iou)
        }
        clearTable()
        // Promises of mine handed back to me: redeeming, with nothing to decide.
        val home = closeIfHome(m.ious)
        val got = Event.Done(got = m.ious)
        return Step(send = home.send, events = listOf(got) + home.events)
    }

    /** An OwU back with its maker is spent - they were the only one who could
     *  be asked. However it arrived, it closes itself and the closed chain
     *  goes back as a receipt. */
    private fun closeIfHome(ious: List<Iou>): Step {
        val send = mutableListOf<PeerMessage>()
        val events = mutableListOf<Event>()
        ious.forEach { iou ->
            val current = store.ious.value[iou.id] ?: iou
            val state = Verifier.verify(current).stateOrNull ?: return@forEach
            if (state.status != Status.ACTIVE || state.debtor != me || state.holder != me) return@forEach
            val closed = Ledger.redeem(current, signer)
            store.put(closed)
            send += PeerMessage.Redeemed(closed)
            events += Event.Redeemed(closed)
        }
        return Step(send = send, events = events)
    }

    /** What both sides say yes to: every OwU pinned to its current head. Both
     *  compute the same string, so a yes crossing a change is refused. */
    private fun deal(): String? {
        if (myOffer.isEmpty() && theirOffer.isEmpty()) return null
        val sides = (myOffer + theirOffer)
            .map { mapOf("iou_id" to it.id, "head_hash" to it.headHash()) }
            .sortedBy { it["iou_id"] }
        return Hash.sha256Hex(Canonical.bytes(sides))
    }

    /** The table changed: both yeses are void. */
    private fun forget() {
        myAccepted = null
        theirAccepted = null
        theirProposal = null
        myProposal = null
    }

    /** Wipes the table after something happened on it: what was minted has moved. */
    private fun clearTable() {
        minted.clear()
        myOffer = emptyList()
        theirOffer = emptyList()
        theirConflict = false
        forget()
    }

    // --- redeem ------------------------------------------------------------

    /** The receipt for an OwU handed back: closed by the one who made it. */
    private fun redeemed(m: PeerMessage.Redeemed): Step {
        val s = state(m.iou)
        if (s.status != Status.REDEEMED || s.debtor != peerKey)
            throw IllegalStateException("that is not a promise of theirs, closed by them")
        keep(m.iou)
        return Step(events = listOf(Event.Redeemed(m.iou)))
    }

    // --- helpers -----------------------------------------------------------

    /** OwUs I hold and could put on the table. */
    fun held(): List<Iou> = store.ious.value.values.filter {
        Verifier.verify(it).stateOrNull?.let { s -> s.holder == me && s.status == Status.ACTIVE } == true
    }

    private fun state(iou: Iou): IouState = when (val v = Verifier.verify(iou)) {
        is Verdict.Valid -> v.state
        is Verdict.Invalid -> throw LedgerException("invalid chain at block ${v.sequence}: ${v.reason}")
    }

    private fun active(iou: Iou): IouState = state(iou).also {
        if (it.status != Status.ACTIVE) throw LedgerException("OwU is ${it.status}")
    }

    /** Stores a chain received as the outcome of a handshake; anything but a clean fit is an error. */
    private fun keep(iou: Iou) {
        when (val r = store.merge(iou)) {
            Merge.Added, Merge.Extended, Merge.Unchanged -> {}
            is Merge.Fork -> throw IllegalStateException("conflicts with the copy you already have")
            is Merge.Invalid -> throw IllegalStateException(r.reason)
        }
    }

    companion object {
        fun authBytes(nonce: String, key: String): ByteArray = "owu-auth-v1\n$nonce\n$key".toByteArray(Charsets.UTF_8)
    }
}

/** Who a message is from, by its signatures or, unsigned, by what it states.
 *  Null when it names nobody. */
fun PeerMessage.counterparty(): String? = when (this) {
    is PeerMessage.Table -> key
    is PeerMessage.Accept -> key
    is PeerMessage.Give -> (ious.firstOrNull()?.head as? Block.Transfer)?.transferor
    is PeerMessage.Redeemed -> Verifier.verify(iou).stateOrNull?.debtor
    else -> null
}
