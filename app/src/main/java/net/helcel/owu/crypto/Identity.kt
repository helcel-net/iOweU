package net.helcel.owu.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Who this phone is: a P-256 pair in the app's storage, sealed with an AES key
 * in the Android Keystore, so a copy of the file is worth nothing elsewhere.
 *
 * Deliberately *not* a Keystore signing key, which can never be read back: the
 * identity would die with the phone and every OwU anyone holds from you would
 * be unredeemable. A backup must outlive the phone, so [export] hands the key
 * over.
 */
class Identity private constructor(private val pair: KeyPair) : Signer {

    override val publicKey: String = Keys.encode(pair.public)

    override fun sign(data: ByteArray): String = Signature.getInstance(Keys.ALGORITHM).run {
        initSign(pair.private)
        update(data)
        Keys.encodeSignature(sign())
    }

    /** Both halves, for a backup the user has asked for: PKCS#8 and X.509 DER. */
    fun export(): Pair<ByteArray, ByteArray> = pair.private.encoded to pair.public.encoded

    companion object {
        private const val FILE = "identity.key"
        private const val ALIAS = "owu_identity_wrap"
        private const val PROVIDER = "AndroidKeyStore"
        private const val TAG_BITS = 128
        private const val NONCE = 12

        fun load(context: Context): Identity {
            val file = File(context.filesDir, FILE)
            if (!file.exists()) {
                val fresh = KeyPairGenerator.getInstance("EC")
                    .apply { initialize(ECGenParameterSpec("secp256r1")) }
                    .generateKeyPair()
                write(file, fresh.private.encoded, fresh.public.encoded)
                return Identity(fresh)
            }
            val (priv, pub) = read(file)
            return Identity(pairOf(priv, pub))
        }

        /**
         * Takes on the identity in a backup, replacing whatever this phone
         * had. The caller asks first: the old identity is gone afterwards, and
         * with it the ability to close promises made under it.
         */
        fun replace(context: Context, pkcs8: ByteArray, x509: ByteArray): Identity {
            val pair = pairOf(pkcs8, x509)   // rejects a mismatched pair below
            write(File(context.filesDir, FILE), pkcs8, x509)
            return Identity(pair)
        }

        /**
         * The pair those two encodings describe, refusing them unless the
         * private half really does sign for the public one - a backup with
         * mismatched halves would be an identity that cannot sign.
         */
        fun pairOf(pkcs8: ByteArray, x509: ByteArray): KeyPair {
            val factory = KeyFactory.getInstance("EC")
            val pair = KeyPair(
                factory.generatePublic(X509EncodedKeySpec(x509)),
                factory.generatePrivate(PKCS8EncodedKeySpec(pkcs8)),
            )
            val proof = Signature.getInstance(Keys.ALGORITHM).run {
                initSign(pair.private)
                update(PROOF)
                sign()
            }
            val good = Signature.getInstance(Keys.ALGORITHM).run {
                initVerify(pair.public)
                update(PROOF)
                verify(proof)
            }
            require(good) { "the two halves of that key do not belong together" }
            return pair
        }

        private val PROOF = "owu-identity-check".toByteArray(Charsets.UTF_8)

        /** The file holds both encodings, length-prefixed, under one seal. */
        private fun read(file: File): Pair<ByteArray, ByteArray> {
            val bytes = file.readBytes()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(TAG_BITS, bytes.copyOf(NONCE)))
            val plain = ByteBuffer.wrap(cipher.doFinal(bytes, NONCE, bytes.size - NONCE))
            val priv = ByteArray(plain.int).also { plain.get(it) }
            val pub = ByteArray(plain.remaining()).also { plain.get(it) }
            return priv to pub
        }

        private fun write(file: File, pkcs8: ByteArray, x509: ByteArray) {
            val plain = ByteBuffer.allocate(4 + pkcs8.size + x509.size)
                .putInt(pkcs8.size).put(pkcs8).put(x509).array()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, wrapKey())
            val sealed = cipher.iv + cipher.doFinal(plain)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeBytes(sealed)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }

        /** The AES key that seals the file, made once and kept in the Keystore. */
        private fun wrapKey(): SecretKey {
            val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
            (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            generator.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            return generator.generateKey()
        }
    }
}
