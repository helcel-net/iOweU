package net.helcel.owu.crypto

import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** An in-memory P-256 identity: what the Keystore provides on a device, minus the vault. */
class JvmSigner(private val pair: KeyPair = generate()) : Signer {

    override val publicKey: String = Keys.encode(pair.public)

    override fun sign(data: ByteArray): String = Signature.getInstance(Keys.ALGORITHM).run {
        initSign(pair.private)
        update(data)
        Keys.encodeSignature(sign())
    }

    /** Writes the pair to [file] so [load] gives the same identity next time. */
    fun save(file: File) {
        file.writeText(
            Base64.getEncoder().encodeToString(pair.private.encoded) + "\n" +
                Base64.getEncoder().encodeToString(pair.public.encoded) + "\n"
        )
    }

    companion object {
        private fun generate(): KeyPair = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()

        /** The identity saved in [file], or a new one saved there. */
        fun load(file: File): JvmSigner {
            if (!file.exists()) return JvmSigner().also { it.save(file) }
            val (priv, pub) = file.readLines().filter { it.isNotBlank() }
            val kf = KeyFactory.getInstance("EC")
            return JvmSigner(
                KeyPair(
                    kf.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(pub))),
                    kf.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(priv))),
                )
            )
        }
    }
}
