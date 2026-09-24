package net.helcel.owu.crypto

/**
 * Something that can sign on behalf of one identity. On a device this is the
 * Keystore-held key; in tests it is an in-memory pair.
 */
interface Signer {
    /** The identity this signer speaks for, as [Keys.encode] gives it. */
    val publicKey: String

    /** ECDSA over SHA-256 of [data], returned as base64 DER. */
    fun sign(data: ByteArray): String
}
