package net.helcel.owu.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CanonicalTest {

    @Test
    fun `keys are sorted and nulls dropped`() {
        val out = Canonical.encode(mapOf("b" to 1, "a" to "x", "c" to null))
        assertEquals("""{"a":"x","b":1}""", out)
    }

    @Test
    fun `nested objects and lists`() {
        val out = Canonical.encode(mapOf("z" to listOf(1, "two", mapOf("k" to true)), "a" to mapOf("y" to 2L, "x" to 1)))
        assertEquals("""{"a":{"x":1,"y":2},"z":[1,"two",{"k":true}]}""", out)
    }

    @Test
    fun `strings are escaped`() {
        val out = Canonical.encode("q\"b\\n\nt\tc\u0001")
        assertEquals(""""q\"b\\n\nt\tc\u0001"""", out)
    }

    @Test
    fun `key order is by code unit not locale`() {
        val out = Canonical.encode(mapOf("é" to 1, "z" to 2, "A" to 3))
        assertEquals("""{"A":3,"z":2,"é":1}""", out)
    }

    @Test
    fun `floating point is refused`() {
        assertFailsWith<IllegalArgumentException> { Canonical.encode(mapOf("x" to 1.5)) }
    }

    @Test
    fun `same content gives same bytes regardless of insertion order`() {
        val a = Canonical.bytes(linkedMapOf("x" to 1, "y" to 2))
        val b = Canonical.bytes(linkedMapOf("y" to 2, "x" to 1))
        assertEquals(a.toList(), b.toList())
    }
}
