package net.helcel.owu.store

import net.helcel.owu.crypto.JvmSigner
import net.helcel.owu.ledger.Ledger
import net.helcel.owu.ledger.Metadata
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A backup is only worth what it can be read back as. */
class BackupTest {

    private val alice = JvmSigner()
    private val contents = Backup.Contents(
        privateKey = Base64.getEncoder().encodeToString(ByteArray(64) { it.toByte() }),
        publicKey = alice.publicKey,
        name = "Ada",
        ious = listOf(Ledger.issue(alice, Metadata("1 Beer"))),
        templates = listOf(Template(UUID.randomUUID().toString(), Metadata("1 Coffee"), Ledger.now())),
        contacts = listOf(Contact(JvmSigner().publicKey, "Dana")),
    )

    @Test
    fun `what goes in comes back out`() {
        val file = Backup.seal(contents, "correct horse".toCharArray())
        assertEquals(contents, Backup.open(file, "correct horse".toCharArray()))
    }

    @Test
    fun `the passphrase matters, and nothing readable is left in the file`() {
        val file = Backup.seal(contents, "correct horse".toCharArray())
        assertFailsWith<BackupException> { Backup.open(file, "Correct horse".toCharArray()) }
        assertFailsWith<BackupException> { Backup.open(file, "".toCharArray()) }
        // The envelope says how to open it, and nothing else: no titles, no
        // names, no keys in the clear.
        val text = file.toString(Charsets.UTF_8)
        assertTrue(text.contains("PBKDF2"), "the envelope names its own parameters")
        // Against the ciphertext itself, not against its base64. Three
        // letters turn up in a few thousand random base64 characters often
        // enough to fail this test about once in a hundred runs, which is
        // exactly often enough to be disbelieved when it matters.
        val sealed = Base64.getDecoder().decode(
            Regex("\"data\":\"([^\"]*)\"").find(text)!!.groupValues[1]
        )
        for (secret in listOf("1 Beer", "1 Coffee", "Ada", "Dana", alice.publicKey)) {
            assertTrue(!text.substringBefore("\"data\"").contains(secret), "found $secret in the envelope")
            assertTrue(sealed.indexOfSub(secret.toByteArray(Charsets.UTF_8)) < 0, "found $secret in the ciphertext")
        }
    }

    @Test
    fun `a changed byte is refused, not half-read`() {
        val file = Backup.seal(contents, "correct horse".toCharArray())
        val text = file.toString(Charsets.UTF_8)
        val at = text.indexOf("\"data\":\"") + 10
        val tampered = (text.substring(0, at) + (if (text[at] == 'A') 'B' else 'A') + text.substring(at + 1))
            .toByteArray(Charsets.UTF_8)
        assertFailsWith<BackupException> { Backup.open(tampered, "correct horse".toCharArray()) }
    }

    @Test
    fun `junk and backups from the future are refused by name`() {
        assertFailsWith<BackupException> { Backup.open("hello".toByteArray(), "x".toCharArray()) }
        assertFailsWith<BackupException> { Backup.open(ByteArray(0), "x".toCharArray()) }
        val future = """{"v":99,"app":"owu","kdf":"PBKDF2WithHmacSHA256","rounds":1,"salt":"AA==","nonce":"AA==","data":"AA=="}"""
        val why = assertFailsWith<BackupException> { Backup.open(future.toByteArray(), "x".toCharArray()) }
        assertTrue(why.message!!.contains("newer"), why.message!!)
    }
}

/** Where [needle] starts in this array, or -1. */
private fun ByteArray.indexOfSub(needle: ByteArray): Int {
    if (needle.isEmpty() || needle.size > size) return -1
    outer@ for (i in 0..size - needle.size) {
        for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
        return i
    }
    return -1
}
