package net.helcel.owu.ble

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Framing on a connection: whole messages out of whatever arrives. */
class FramesTest {
    private val me = ByteArray(8) { (it + 1).toByte() }
    private val frames = Frames()

    private fun bytes(n: Int) = ByteArray(n) { (it * 7).toByte() }

    @Test
    fun `a message arrives whole however it is cut up`() {
        val message = bytes(1000)
        val frame = frames.frame(me, message)
        val reader = Frames()
        val chunks = frames.chunks(frame, 20)
        assertTrue(chunks.size > 40, "cut into ${chunks.size}")
        val got = chunks.flatMap { reader.feed(it) }
        assertEquals(1, got.size)
        assertContentEquals(me, got[0].first)
        assertContentEquals(message, got[0].second)
    }

    @Test
    fun `several messages in one arrival come out in order`() {
        val reader = Frames()
        val one = frames.frame(me, "one".toByteArray())
        val two = frames.frame(me, "two".toByteArray())
        val got = reader.feed(one + two)
        assertEquals(listOf("one", "two"), got.map { String(it.second) })
    }

    @Test
    fun `a partial message waits, and an absurd length drops the stream`() {
        val reader = Frames()
        val frame = frames.frame(me, bytes(100))
        assertTrue(reader.feed(frame.copyOfRange(0, 40)).isEmpty(), "not whole yet")
        assertEquals(1, reader.feed(frame.copyOfRange(40, frame.size)).size)

        // A length nothing could produce: the buffer is dropped, not grown.
        assertTrue(reader.feed(byteArrayOf(0x7f, -1, -1, -1)).isEmpty())
        val after = reader.feed(frames.frame(me, "after".toByteArray()))
        assertEquals(listOf("after"), after.map { String(it.second) })
    }
}
