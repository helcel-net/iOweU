package net.helcel.owu.tools

import net.helcel.owu.ledger.GeoLoc
import net.helcel.owu.ledger.IouJson
import net.helcel.owu.ledger.Ledger
import net.helcel.owu.ledger.Metadata
import net.helcel.owu.ledger.Verdict
import net.helcel.owu.ledger.Verifier

import net.helcel.owu.crypto.JvmSigner
import java.io.File
import kotlin.test.Test

/**
 * Not a test: a generator for on-device smoke testing. Set
 * OWU_FIXTURE_CREDITOR to a device's public key and OWU_FIXTURE_OUT to a
 * directory, and it writes OwUs that device holds, plus a contacts file
 * naming the other parties. Does nothing otherwise.
 *
 *   OWU_FIXTURE_CREDITOR=MFkw... OWU_FIXTURE_OUT=/tmp/fx ./gradlew testDebugUnitTest --tests '*FixtureTest*'
 */
class FixtureTest {
    @Test
    fun generate() {
        val creditor = System.getenv("OWU_FIXTURE_CREDITOR") ?: return
        val out = File(System.getenv("OWU_FIXTURE_OUT") ?: return).apply { mkdirs() }
        val dana = JvmSigner()
        val eli = JvmSigner()

        // Held by the device, tied to a place.
        val coffee = Ledger.issue(
            dana, creditor = creditor,
            metadata = Metadata("1 Coffee", geoloc = GeoLoc.of(46.5197, 6.6323, 150, "Café du Lac")),
        )
        // Held by the device, no place.
        val hug = Ledger.issue(dana, creditor = creditor, metadata = Metadata("1 Heavy Hug"))
        // Seen but not ours: dana owes eli, transferred once.
        val ride = Ledger.transfer(Ledger.issue(dana, creditor = eli.publicKey, metadata = Metadata("1 Ride to the airport")), eli, creditor)
        // Held by the device, not redeemable for two days / expired yesterday.
        val now = Ledger.now()
        val cinema = Ledger.issue(dana, creditor = creditor, metadata = Metadata("1 Cinema ticket", notBefore = now + 2 * 86400, notAfter = now + 30 * 86400))
        val lunch = Ledger.issue(dana, creditor = creditor, metadata = Metadata("1 Lunch", notAfter = now - 86400))
        // Redeemed between others: dana handed it back and eli closed it.
        var beer = Ledger.issue(eli, creditor = dana.publicKey, metadata = Metadata("1 Beer"))
        beer = Ledger.redeem(Ledger.transfer(beer, dana, eli.publicKey), eli)

        for (iou in listOf(coffee, hug, ride, beer, cinema, lunch)) {
            File(out, "${iou.id}.json").writeText(IouJson.encode(iou))
        }
        File(out, "contacts.json").writeText(
            """[{"pub_key":"${dana.publicKey}","name":"Dana"},{"pub_key":"${eli.publicKey}","name":"Eli"}]"""
        )
        println("fixtures written to $out")
    }
}

/** Also not a test: verifies a chain file from a device. Set OWU_VERIFY_FILE. */
class VerifyFileTest {
    @Test
    fun verify() {
        val path = System.getenv("OWU_VERIFY_FILE") ?: return
        val iou = IouJson.decode(File(path).readText())
        val verdict = Verifier.verify(iou)
        println("verify $path -> $verdict")
        check(verdict is Verdict.Valid) { "device chain rejected: $verdict" }
    }
}

/** Also not a test: decodes the identity QR from a screenshot. Set OWU_QR_PNG. */
class DecodeQrTest {
    @Test
    fun decode() {
        val path = System.getenv("OWU_QR_PNG") ?: return
        val img = javax.imageio.ImageIO.read(File(path))
        val pixels = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
        val bitmap = com.google.zxing.BinaryBitmap(
            com.google.zxing.common.HybridBinarizer(com.google.zxing.RGBLuminanceSource(img.width, img.height, pixels))
        )
        val hints = mapOf(com.google.zxing.DecodeHintType.TRY_HARDER to true)
        val text = com.google.zxing.qrcode.QRCodeReader().decode(bitmap, hints).text
        println("qr decoded -> $text")
        System.getenv("OWU_QR_OUT")?.let { File(it).writeText(text) }
        net.helcel.owu.helper.IdentityCard.decode(text)?.let {
            println("qr card -> name='${it.name}' fingerprint=${net.helcel.owu.crypto.Keys.fingerprint(it.key)}")
        }
    }
}
