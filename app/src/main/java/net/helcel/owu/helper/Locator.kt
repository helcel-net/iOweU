package net.helcel.owu.helper

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import net.helcel.owu.ledger.GeoLoc
import kotlin.coroutines.resume

/**
 * One fix, straight from the platform's LocationManager. No fused provider:
 * that is Play Services.
 */
object Locator {
    const val PERMISSION = Manifest.permission.ACCESS_FINE_LOCATION

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Metres from [location] to the centre of [geoloc]. */
    fun distanceTo(location: Location, geoloc: GeoLoc): Float {
        val out = FloatArray(1)
        Location.distanceBetween(location.latitude, location.longitude, geoloc.latitude, geoloc.longitude, out)
        return out[0]
    }

    /**
     * The current position, waiting up to [timeoutMs] for a fresh fix and
     * falling back to the last known one. Null if the device has neither.
     */
    // Lint cannot see the permission check through the suspend boundary; it is the first line.
    @SuppressLint("MissingPermission")
    suspend fun current(context: Context, timeoutMs: Long = 10_000): Location? {
        if (!hasPermission(context)) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
            ?: return null
        val fresh = withTimeoutOrNull(timeoutMs) { request(context, manager, provider) }
        return fresh ?: runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
    }

    @SuppressLint("MissingPermission") // only reached through [current], which checks
    private suspend fun request(context: Context, manager: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                manager.getCurrentLocation(provider, signal, context.mainExecutor) { if (cont.isActive) cont.resume(it) }
            } else {
                // All four methods spelled out: before API 30 none of them
                // has a default, and a lambda would miss the other three.
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (cont.isActive) cont.resume(location)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
                @Suppress("DEPRECATION")
                manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                cont.invokeOnCancellation { manager.removeUpdates(listener) }
            }
        }
}
