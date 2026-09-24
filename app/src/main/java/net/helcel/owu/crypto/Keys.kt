package net.helcel.owu.crypto

import java.math.BigInteger
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Public keys travel as base64 of their X.509 SubjectPublicKeyInfo DER, the
 * form `PublicKey.getEncoded()` gives for an EC key on every platform. That
 * string is a user's identity: it is what IOUs are issued and transferred to.
 */
object Keys {
    const val ALGORITHM = "SHA256withECDSA"

    fun encode(key: PublicKey): String = Base64.getEncoder().encodeToString(key.encoded)

    fun decode(encoded: String): PublicKey? = try {
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(encoded)))
    } catch (e: Exception) {
        null
    }

    /**
     * True if [signature] (base64 DER) verifies [data] under [publicKey] **and**
     * is in the one encoding we accept.
     *
     * ECDSA admits two signatures per key and message - (r, s) and (r, n - s) -
     * and DER admits padding. Either lets anyone who has merely *seen* an OwU
     * rewrite its head signature: still valid, different block hash, so the two
     * copies look like a double-spend by their holder. Low-s minimal DER only,
     * which makes a fork mean what it says: someone signed twice.
     */
    fun verify(publicKey: String, data: ByteArray, signature: String): Boolean {
        val key = decode(publicKey) ?: return false
        val der = try {
            Base64.getDecoder().decode(signature)
        } catch (e: Exception) {
            return false
        }
        if (!isCanonical(der)) return false
        return try {
            Signature.getInstance(ALGORITHM).run {
                initVerify(key)
                update(data)
                verify(der)
            }
        } catch (e: Exception) {
            false
        }
    }

    /** A signature as it must be stored: minimal DER, low s, base64. */
    fun encodeSignature(der: ByteArray): String =
        Base64.getEncoder().encodeToString(canonical(der))

    /** The same signature in the one accepted encoding. */
    fun canonical(der: ByteArray): ByteArray {
        val (r, s) = parse(der) ?: return der
        return encodePair(r, if (s > HALF_ORDER) ORDER.subtract(s) else s)
    }

    fun isCanonical(der: ByteArray): Boolean {
        val (r, s) = parse(der) ?: return false
        return s <= HALF_ORDER && der.contentEquals(encodePair(r, s))
    }

    /** (r, s) of a DER SEQUENCE of two INTEGERs, or null if it is not one. */
    private fun parse(der: ByteArray): Pair<BigInteger, BigInteger>? {
        // P-256 signatures are far below 128 bytes, so only short-form
        // lengths are ever legitimate here.
        if (der.size < 8 || der.size > 72 || der[0] != 0x30.toByte()) return null
        if ((der[1].toInt() and 0xff) != der.size - 2) return null
        fun integer(at: Int): Pair<BigInteger, Int>? {
            if (at + 1 >= der.size || der[at] != 0x02.toByte()) return null
            val length = der[at + 1].toInt() and 0xff
            if (length == 0 || at + 2 + length > der.size) return null
            val bytes = der.copyOfRange(at + 2, at + 2 + length)
            // Minimal encoding: no leading zero unless the next byte would
            // read as negative, and never negative itself.
            if (bytes[0].toInt() and 0x80 != 0) return null
            if (bytes.size > 1 && bytes[0] == 0.toByte() && bytes[1].toInt() and 0x80 == 0) return null
            return BigInteger(1, bytes) to at + 2 + length
        }
        val (r, next) = integer(2) ?: return null
        val (s, end) = integer(next) ?: return null
        if (end != der.size) return null
        if (r.signum() <= 0 || s.signum() <= 0 || r >= ORDER || s >= ORDER) return null
        return r to s
    }

    private fun encodePair(r: BigInteger, s: BigInteger): ByteArray {
        fun integer(v: BigInteger): ByteArray {
            val bytes = v.toByteArray()   // already minimal and non-negative
            return byteArrayOf(0x02, bytes.size.toByte()) + bytes
        }

        val body = integer(r) + integer(s)
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    /** The order of the P-256 group, and the line between low and high s. */
    private val ORDER = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)
    private val HALF_ORDER = ORDER.shiftRight(1)

    /**
     * A short, human-comparable handle for a key: the first 64 bits of the
     * SHA-256 of its DER, as four groups of hex. For display only.
     */
    fun fingerprint(publicKey: String): String {
        val der = try {
            Base64.getDecoder().decode(publicKey)
        } catch (e: Exception) {
            publicKey.toByteArray()
        }
        return Hash.sha256Hex(der).take(16).chunked(4).joinToString(" ")
    }
}
