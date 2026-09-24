package net.helcel.owu.peer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.helcel.owu.helper.IdentityCard
import net.helcel.owu.ledger.Iou
import net.helcel.owu.ledger.Metadata
import net.helcel.owu.ledger.Verifier
import java.util.concurrent.atomic.AtomicInteger

/**
 * One device as we know it: its [PeerEngine], the messages we owe it (sent in
 * order, one at a time), and state for the screens. It exists while the peer
 * is heard; the connection underneath is the [net.helcel.owu.ble.Link]'s.
 */
class PeerSession(
    val beacon: ByteArray,
    private val engine: PeerEngine,
    /** Delivers one message; true once acked. With `dial`, connects first if need be. */
    private val send: suspend (bytes: ByteArray, dial: Boolean) -> Boolean,
    parent: CoroutineScope,
    /** Called once for each table that finished, so something can say so out loud. */
    private val onOutcome: (Outcome) -> Unit = {},
) {
    enum class Phase { HANDSHAKE, READY, GONE }

    data class State(
        val phase: Phase = Phase.HANDSHAKE,
        val peer: IdentityCard? = null,
        val rssi: Int = 0,
        val log: List<String> = emptyList(),
        /** What the two of us have put on the table, and who has said yes. */
        val table: PeerEngine.Table = PeerEngine.Table(),
        /** What we are waiting on the peer for, if anything. */
        val waiting: String? = null,
        /** The last thing that did not reach them, so a screen can say so. */
        val undelivered: String? = null,
        /** They have asked us to the table and we have neither come nor declined. */
        val invited: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val mutex = Mutex()

    /** Messages to send, each with the log line that belongs to it once it has arrived. */
    private val outbox = Channel<Pair<PeerMessage, String?>>(Channel.UNLIMITED)

    /** Messages queued and not yet on the air or acked. */
    private val inFlight = AtomicInteger(0)

    init {
        scope.launch {
            for ((m, line) in outbox) {
                // A HELLO is said into the dark; anything else is a person's
                // action and worth a connection. This is "redeem finds them".
                if (send(PeerMessage.encode(m), m !is PeerMessage.Hello)) {
                    if (line != null) log(line)
                } else if (m !is PeerMessage.Hello) {
                    // An unanswered HELLO is ordinary; we say it again presently.
                    // A user's action that did not arrive is not, so say so.
                    val what = line ?: m::class.simpleName.orEmpty()
                    _state.update {
                        it.copy(log = it.log + "Not delivered: $what", waiting = null, undelivered = what)
                    }
                }
                inFlight.decrementAndGet()
            }
        }
        perform { engine.start() }
        // Until verified, say hello again: the first may have gone out before
        // the peer was listening.
        scope.launch {
            while (_state.value.phase == Phase.HANDSHAKE) {
                delay(REINTRODUCE_MS)
                if (_state.value.phase == Phase.HANDSHAKE && inFlight.get() == 0) perform { engine.reintroduce() }
            }
        }
    }

    /** A message from this peer, off the air. */
    fun deliver(bytes: ByteArray) = perform { engine.onMessage(PeerMessage.decode(bytes)) }

    fun heard(rssi: Int) = _state.update { it.copy(rssi = rssi) }

    // --- user actions ------------------------------------------------------

    /** My side of the table: ious I hold plus one minted per template. Empty takes it off. */
    fun put(held: List<Iou> = emptyList(), mint: List<Metadata> = emptyList()) = perform { engine.put(held, mint) }

    /** Mint one OwU from a template and put that on the table by itself. */
    fun putNew(metadata: Metadata) = perform { engine.putNew(metadata) }

    /** Say yes to the table as it stands. */
    fun accept() = perform(waiting = "them to accept") { engine.accept() }

    /** Ask them to the table just opened; until this they had no way of knowing. */
    fun invite() = perform { engine.invite() }

    /** Their invitation has been answered, one way or the other. */
    fun inviteAnswered() = _state.update { it.copy(invited = false) }

    /** No, not just now - and say so, rather than leaving them waiting. */
    fun decline(to: PeerMessage.Asked) = perform { engine.decline(to) }

    /** Put [ious] down and say yes in one move, under one lock so the yes
     *  cannot overtake the ious it is about. */
    fun offer(ious: List<Iou>) = perform(waiting = "them to accept") {
        val down = engine.put(held = ious)
        val yes = engine.accept()
        // One message, not two: the acceptance carries its own ious, so a
        // TABLE first only adds something that can go missing. Its events
        // still fire, so this side's screens see what was put down.
        PeerEngine.Step(send = yes.send, events = down.events + yes.events)
    }

    /** Give up on an answer that is not coming; whatever was pending stays valid if it does. */
    fun stopWaiting() = _state.update { it.copy(waiting = null) }

    /** The peer has not been heard for a while. */
    fun gone() {
        // Anything minted for a table that never happened goes with them.
        runCatching { engine.abandon() }
        scope.cancel()
        outbox.close()
        _state.update { it.copy(phase = Phase.GONE, waiting = null) }
    }

    // --- plumbing ----------------------------------------------------------

    /** Runs one engine step off the calling thread. [line] is logged once the
     *  last message is acked, so "Sent" means sent. */
    private fun perform(line: String? = null, waiting: String? = null, block: () -> PeerEngine.Step) {
        scope.launch {
            try {
                mutex.withLock {
                    // A fresh attempt: whatever failed last time is history.
                    _state.update { it.copy(undelivered = null) }
                    val step = block()
                    step.send.forEachIndexed { i, m ->
                        inFlight.incrementAndGet()
                        outbox.send(m to line.takeIf { i == step.send.lastIndex })
                    }
                    for (e in step.events) handle(e)
                    announce(step.events)
                    if (step.send.isEmpty() && line != null) log(line)
                    // Nothing to wait for if the step already finished it.
                    if (waiting != null && step.events.none { it is PeerEngine.Event.Done }) {
                        _state.update { it.copy(waiting = waiting) }
                    }
                }
            } catch (e: Exception) {
                log("Error: ${e.message}")
            }
        }
    }

    private fun handle(e: PeerEngine.Event) {
        when (e) {
            // No log line: the tick beside their key says it where it matters.
            is PeerEngine.Event.PeerIdentified -> _state.update {
                it.copy(phase = Phase.READY, peer = e.card)
            }

            PeerEngine.Event.Restarted -> _state.update {
                State(
                    phase = Phase.HANDSHAKE,
                    peer = it.peer,
                    rssi = it.rssi,
                    log = it.log + "Peer came back; verifying again"
                )
            }

            is PeerEngine.Event.Tabled -> _state.update { it.copy(table = e.table) }
            is PeerEngine.Event.Done -> {
                _state.update { it.copy(table = PeerEngine.Table()) }
                val gave = summarise(e.gave)
                val got = summarise(e.got)
                answered(
                    when {
                        gave != null && got != null -> "Traded $gave for $got"
                        gave != null -> "Gave $gave"
                        got != null -> "Got $got"
                        else -> "Nothing changed hands"
                    }
                )
            }

            is PeerEngine.Event.Redeemed -> {
                // A redemption ends the table it was on, on both sides.
                _state.update { it.copy(table = PeerEngine.Table()) }
                answered("${e.iou.metadata.title} redeemed")
            }

            is PeerEngine.Event.Invited -> _state.update { it.copy(invited = true) }
            // Nothing is being waited for any more; their answer was no.
            is PeerEngine.Event.Declined -> _state.update { it.copy(waiting = null) }
            is PeerEngine.Event.Failed -> log("Error: ${e.reason}")
        }
    }

    /**
     * One announcement per step, or none. An OwU going home is not announced
     * as given but when its closed receipt returns, which is when it happened;
     * on their side gift and receipt are one step. Either way, named once.
     */
    private fun announce(events: List<PeerEngine.Event>) {
        val done = events.filterIsInstance<PeerEngine.Event.Done>()
        val redeemed = events.filterIsInstance<PeerEngine.Event.Redeemed>().map { it.iou }
        val closed = redeemed.map { it.id }.toSet()
        val peer = _state.value.peer?.key
        val outcome = Outcome(
            peer = peer,
            gave = done.flatMap { it.gave }.filter { it.id !in closed && !goesHome(it, peer) },
            got = done.flatMap { it.got }.filter { it.id !in closed },
            redeemed = redeemed,
            declined = events.filterIsInstance<PeerEngine.Event.Declined>().firstOrNull()?.to,
        )
        if (!outcome.isEmpty) onOutcome(outcome)
    }

    /** A promise of theirs, on its way back to them: a redemption in progress. */
    private fun goesHome(iou: Iou, peer: String?): Boolean =
        peer != null && Verifier.verify(iou).stateOrNull?.debtor == peer

    private fun log(line: String) = _state.update { it.copy(log = it.log + line) }
    private fun answered(line: String) = _state.update { it.copy(log = it.log + line, waiting = null) }

    companion object {
        private const val REINTRODUCE_MS = 6_000L
    }
}

/** A bundle in one line: "3 × 1 Beer, 1 Heavy Hug", not the title three times. */
private fun summarise(ious: List<net.helcel.owu.ledger.Iou>): String? {
    if (ious.isEmpty()) return null
    return ious.groupingBy { it.metadata.title }.eachCount().entries
        .joinToString(", ") { (title, n) -> if (n > 1) "$n × $title" else title }
}
