package net.helcel.owu.store

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.helcel.owu.ledger.Iou
import net.helcel.owu.ledger.IouJson
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** What went wrong opening a backup, in terms the screen can repeat. */
class BackupException(message: String) : Exception(message)

/**
 * Everything this phone is in one file: identity, OwUs, templates, contacts.
 *
 * Encrypted under a passphrase, because it holds a private key - whoever opens
 * it can *be* you. AES-GCM under a PBKDF2 key, parameters in the clear so an
 * older file still opens when they change.
 */
object Backup {

    @Serializable
    data class Contents(
        /** The identity's private half, PKCS#8 DER, base64. */
        @SerialName("private_key") val privateKey: String,
        /** Its public half, X.509 DER, base64 - kept so the pair can be checked. */
        @SerialName("public_key") val publicKey: String,
        val name: String = "",
        val ious: List<Iou> = emptyList(),
        val templates: List<Template> = emptyList(),
        val contacts: List<Contact> = emptyList(),
    )

    @Serializable
    private data class Envelope(
        val v: Int = VERSION,
        val app: String = "owu",
        val kdf: String = "PBKDF2WithHmacSHA256",
        val rounds: Int = ROUNDS,
        val salt: String,
        val nonce: String,
        val data: String,
    )

    const val VERSION = 1
    private const val ROUNDS = 210_000
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128
    private const val SALT = 16
    private const val NONCE = 12

    /** The bytes to write to the file the user chose. */
    fun seal(contents: Contents, passphrase: CharArray): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT).also(random::nextBytes)
        val nonce = ByteArray(NONCE).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key(passphrase, salt, ROUNDS), GCMParameterSpec(TAG_BITS, nonce))
        }
        val plain = IouJson.json.encodeToString(Contents.serializer(), contents).toByteArray(Charsets.UTF_8)
        val envelope = Envelope(
            salt = base64(salt),
            nonce = base64(nonce),
            data = base64(cipher.doFinal(plain)),
        )
        return IouJson.json.encodeToString(Envelope.serializer(), envelope).toByteArray(Charsets.UTF_8)
    }

    /** What is in a backup file, or [BackupException] saying why not. */
    fun open(bytes: ByteArray, passphrase: CharArray): Contents {
        val envelope = try {
            IouJson.json.decodeFromString(Envelope.serializer(), bytes.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw BackupException("that is not an OwU backup")
        }
        if (envelope.app != "owu" || envelope.v > VERSION) throw BackupException("that backup is from a newer OwU")
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(
                    Cipher.DECRYPT_MODE,
                    key(passphrase, unbase64(envelope.salt), envelope.rounds),
                    GCMParameterSpec(TAG_BITS, unbase64(envelope.nonce)),
                )
            }
            cipher.doFinal(unbase64(envelope.data))
        } catch (e: AEADBadTagException) {
            // One message for both: a wrong passphrase and a changed byte are
            // the same event to AES-GCM, and telling them apart helps nobody.
            throw BackupException("wrong passphrase, or the file has been damaged")
        } catch (e: Exception) {
            throw BackupException("that backup could not be read")
        }
        return try {
            IouJson.json.decodeFromString(Contents.serializer(), plain.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw BackupException("that backup could not be read")
        }
    }

    private fun key(passphrase: CharArray, salt: ByteArray, rounds: Int) = SecretKeySpec(
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(passphrase, salt, rounds, KEY_BITS)).encoded,
        "AES",
    )

    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    private fun unbase64(text: String): ByteArray = Base64.getDecoder().decode(text)
}
