package net.helcel.owu.crypto


import java.math.BigInteger
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeysTest {

    @Test
    fun `sign and verify round trip`() {
        val s = JvmSigner()
        val data = "hello".toByteArray()
        val sig = s.sign(data)
        assertTrue(Keys.verify(s.publicKey, data, sig))
        assertFalse(Keys.verify(s.publicKey, "hellp".toByteArray(), sig))
        assertFalse(Keys.verify(JvmSigner().publicKey, data, sig))
    }

    @Test
    fun `garbage never verifies or throws`() {
        val s = JvmSigner()
        assertFalse(Keys.verify(s.publicKey, "x".toByteArray(), "not base64!!"))
        assertFalse(Keys.verify(s.publicKey, "x".toByteArray(), ""))
        assertFalse(Keys.verify("not a key", "x".toByteArray(), s.sign("x".toByteArray())))
        assertNull(Keys.decode("AAAA"))
        assertNotNull(Keys.decode(s.publicKey))
    }

    @Test
    fun `the other valid form of the same signature is refused`() {
        val signer = JvmSigner()
        val data = "a block".toByteArray()
        val signature = signer.sign(data)
        assertTrue(Keys.verify(signer.publicKey, data, signature))

        // ECDSA verifies (r, s) and (r, n - s) alike: anyone who has seen the
        // signature can make the second one. It must not be accepted, or a
        // OwU could be given two head hashes by a stranger.
        val der = Base64.getDecoder().decode(signature)
        val flipped = highForm(der)
        assertFalse(der.contentEquals(flipped), "the high form differs")
        assertTrue(
            verifiesRaw(signer.publicKey, data, flipped),
            "ECDSA itself still accepts it, which is the point",
        )
        assertFalse(Keys.verify(signer.publicKey, data, Base64.getEncoder().encodeToString(flipped)))
        assertTrue(Keys.canonical(flipped).contentEquals(der), "and it maps back to the one we keep")
    }

    @Test
    fun `padded or malformed DER is refused`() {
        val signer = JvmSigner()
        val data = "x".toByteArray()
        val der = Base64.getDecoder().decode(signer.sign(data))
        // A leading zero on r that minimal DER would not write.
        val padded = der.copyOfRange(0, 2) + byteArrayOf(0x02, (der[3] + 1).toByte(), 0) +
            der.copyOfRange(4, der.size)
        padded[1] = (padded.size - 2).toByte()
        assertFalse(Keys.verify(signer.publicKey, data, Base64.getEncoder().encodeToString(padded)))
        assertFalse(Keys.verify(signer.publicKey, data, Base64.getEncoder().encodeToString(byteArrayOf(0x30, 0))))
    }

    /** The same signature with s replaced by n - s, re-encoded. */
    private fun highForm(der: ByteArray): ByteArray {
        val order = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)
        val rLen = der[3].toInt()
        val r = BigInteger(1, der.copyOfRange(4, 4 + rLen))
        val sLen = der[5 + rLen].toInt()
        val s = BigInteger(1, der.copyOfRange(6 + rLen, 6 + rLen + sLen))
        val other = order.subtract(s)
        fun int(v: BigInteger) = byteArrayOf(0x02, v.toByteArray().size.toByte()) + v.toByteArray()
        val body = int(r) + int(other)
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    private fun verifiesRaw(publicKey: String, data: ByteArray, der: ByteArray): Boolean =
        java.security.Signature.getInstance(Keys.ALGORITHM).run {
            initVerify(Keys.decode(publicKey))
            update(data)
            verify(der)
        }

    @Test
    fun `fingerprint is short and stable`() {
        val k = JvmSigner().publicKey
        assertEquals(Keys.fingerprint(k), Keys.fingerprint(k))
        assertEquals(19, Keys.fingerprint(k).length)
    }
}
