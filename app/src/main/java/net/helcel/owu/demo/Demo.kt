package net.helcel.owu.demo

import net.helcel.owu.crypto.Keys
import net.helcel.owu.crypto.Signer
import net.helcel.owu.ledger.GeoLoc
import net.helcel.owu.ledger.Iou
import net.helcel.owu.ledger.Ledger
import net.helcel.owu.ledger.Metadata
import net.helcel.owu.store.Contact
import net.helcel.owu.store.IouStore
import net.helcel.owu.store.Template
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID

/**
 * A ledger worth looking at, debug only: its one caller sits behind
 * `BuildConfig.DEBUG`, so R8 drops all of this from a release.
 *
 * Signed for real by throwaway keys, so these verify, trade and redeem like
 * any other, and cover the states a screen can be in: held, owed, arrived
 * through a third party, place-bound, not yet valid, long expired, over.
 */
object Demo {

    /** A key that lives only as long as this demo needs it. */
    private class SoftSigner : Signer {
        private val pair = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()

        override val publicKey: String = Keys.encode(pair.public)

        override fun sign(data: ByteArray): String = Signature.getInstance(Keys.ALGORITHM).run {
            initSign(pair.private)
            update(data)
            Keys.encodeSignature(sign())
        }
    }

    private const val HOUR = 3600L
    private const val DAY = 24 * HOUR

    /** True when there is nothing here yet, and so nothing to disturb. */
    fun wanted(store: IouStore): Boolean =
        store.ious.value.isEmpty() && store.templates.value.isEmpty()

    fun fill(store: IouStore, me: Signer) {
        val now = Ledger.now()
        val dana = SoftSigner()
        val eli = SoftSigner()
        val mo = SoftSigner()
        val kim = SoftSigner()

        // Two people you have named, one you have only met - so an OwU owed by
        // Mo shows without the tick - and one who only ever said hello.
        store.putContact(Contact(dana.publicKey, "Dana"))
        store.putContact(Contact(eli.publicKey, "Eli"))
        store.putMet(mo.publicKey, "Mo")
        store.putMet(kim.publicKey, "Kim")

        // Promises you keep ready.
        listOf(
            Metadata("1 Beer"),
            Metadata("1 Coffee", geoloc = GeoLoc.of(46.5197, 6.6323, 150, "Café du Lac")),
            Metadata("1 Ride to the airport", "Any terminal, any hour. Wake me if you must."),
            Metadata("1 Heavy Hug", notAfter = now + 30 * DAY),
        ).forEachIndexed { i, metadata ->
            store.putTemplate(Template(UUID.randomUUID().toString(), metadata, now - i * HOUR))
        }

        // OwUs you hold, from people who owe you.
        store.put(
            handedOver(
                dana,
                me.publicKey,
                Metadata("1 Coffee", geoloc = GeoLoc.of(46.5197, 6.6323, 150, "Café du Lac")),
                now - 30 * HOUR
            )
        )
        store.put(handedOver(eli, me.publicKey, Metadata("1 Ride to the airport"), now - 26 * HOUR))
        store.put(
            handedOver(
                dana,
                me.publicKey,
                Metadata("1 Lunch", "Somewhere with a terrace.", notAfter = now - DAY),
                now - 20 * DAY
            )
        )
        store.put(
            handedOver(
                eli,
                me.publicKey,
                Metadata("1 Concert ticket", notBefore = now + 2 * DAY),
                now - 8 * HOUR
            )
        )

        // One that reached you through somebody else: Mo promised it, Dana
        // passed it on. Mo is no contact of yours, so it reads as a stranger's.
        val third = handedOver(mo, dana.publicKey, Metadata("1 Cinema ticket"), now - 12 * HOUR)
        store.put(Ledger.transfer(third, dana, me.publicKey, now - 6 * HOUR))

        // OwUs you owe.
        store.put(handedOver(me, dana.publicKey, Metadata("1 Massage"), now - 18 * HOUR))
        store.put(handedOver(me, eli.publicKey, Metadata("2 Beers"), now - 16 * HOUR))

        // And one that is over: handed home to Dana, who closed it.
        val tea = handedOver(dana, me.publicKey, Metadata("1 Tea"), now - 5 * DAY)
        val home = Ledger.transfer(tea, me, dana.publicKey, now - 4 * DAY)
        store.put(Ledger.redeem(home, dana, now - 4 * DAY + HOUR))
    }

    /**
     * A promise written by [debtor] and handed to [holder], which is what
     * every OwU that has moved looks like: a genesis to oneself, then a
     * transfer.
     */
    private fun handedOver(debtor: Signer, holder: String, metadata: Metadata, at: Long): Iou =
        Ledger.transfer(Ledger.issue(debtor, metadata, timestamp = at), debtor, holder, at + 60)
}
