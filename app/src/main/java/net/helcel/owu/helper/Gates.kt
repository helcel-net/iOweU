package net.helcel.owu.helper

import android.content.Context
import net.helcel.owu.ledger.Iou

/** What an OwU's own conditions say about here and now. */
object Gates {
    /**
     * True when the OwU names a place and we are elsewhere. Never a bar: it
     * only paints the line red, since only the one who wrote it can say
     * whether it counts. Without permission or a fix we cannot say we are
     * away, so we do not say it.
     */
    suspend fun outOfPlace(context: Context, iou: Iou): Boolean {
        val geo = iou.metadata.geoloc ?: return false
        if (!Locator.hasPermission(context)) return false
        val loc = Locator.current(context) ?: return false
        return Locator.distanceTo(loc, geo) > geo.radiusM
    }
}
