package net.helcel.owu.crypto

import java.security.MessageDigest

object Hash {
    /** The parent hash of a genesis block: nothing came before it. */
    const val ZERO = "0000000000000000000000000000000000000000000000000000000000000000"

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun sha256Hex(bytes: ByteArray): String = hex(sha256(bytes))

    fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) append("%02x".format(b))
    }
}
