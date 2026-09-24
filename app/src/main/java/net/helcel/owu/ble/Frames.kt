package net.helcel.owu.ble

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Messages over a link that delivers bytes in order, in any sizes. A frame is
 * `length (4) + sender beacon (8) + payload`, the length covering both.
 *
 * The beacon rides every frame: a connection says which *device* is talking,
 * not which identity, and the side that accepted it may never have heard that
 * device advertise.
 */
class Frames(private val limit: Int = MAX_MESSAGE) {
    private val buffer = ByteArrayOutputStream()

    /** One message, ready to be written in whatever chunks the link takes. */
    fun frame(from: ByteArray, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(4 + BEACON + payload.size)
            .putInt(BEACON + payload.size)
            .put(from, 0, BEACON)
            .put(payload)
            .array()

    /** Feeds what arrived, returning whatever is now whole as (beacon, payload).
     *  A frame longer than [limit] is not ours, so the stream is dropped. */
    fun feed(bytes: ByteArray): List<Pair<ByteArray, ByteArray>> {
        buffer.write(bytes)
        val out = mutableListOf<Pair<ByteArray, ByteArray>>()
        var have = buffer.toByteArray()
        while (have.size >= 4) {
            val length = ByteBuffer.wrap(have, 0, 4).int
            if (length < BEACON || length > limit) {
                buffer.reset()
                return out
            }
            if (have.size < 4 + length) break
            out += have.copyOfRange(4, 4 + BEACON) to have.copyOfRange(4 + BEACON, 4 + length)
            have = have.copyOfRange(4 + length, have.size)
        }
        buffer.reset()
        buffer.write(have)
        return out
    }

    /** Forgets a half-received frame, as when a connection drops. */
    fun clear() = buffer.reset()

    /** Cuts [frame] into pieces of at most [size] bytes, as one link write each. */
    fun chunks(frame: ByteArray, size: Int): List<ByteArray> {
        require(size > 0) { "chunk size" }
        return (frame.indices step size).map { frame.copyOfRange(it, minOf(it + size, frame.size)) }
    }

    companion object {
        const val BEACON = 8
        /** Nothing this app sends comes near this; anything longer is a broken stream. */
        const val MAX_MESSAGE = 1 shl 18
    }
}
