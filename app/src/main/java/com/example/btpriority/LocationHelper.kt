package com.example.btpriority

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
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.function.Consumer

object LocationHelper {
    private const val TAG = "LocationHelper"

    fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    /**
     * Reads cached system location without triggering active GPS radio power (0% battery drain).
     */
    @SuppressLint("MissingPermission")
    fun getLastKnownLocation(context: Context): Location? {
        if (!hasLocationPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null

        var bestLocation: Location? = null
        val providers = try { lm.getProviders(true) } catch (_: Exception) { emptyList() }

        for (provider in providers) {
            val location = try {
                lm.getLastKnownLocation(provider)
            } catch (e: Exception) {
                null
            } ?: continue

            if (bestLocation == null || location.time > bestLocation.time) {
                bestLocation = location
            }
        }
        return bestLocation
    }

    /**
     * Checks if device is currently within the specified geofence radius.
     * Uses cached location to preserve battery.
     */
    fun isInsideGeofence(
        context: Context,
        targetLat: Double,
        targetLng: Double,
        radiusMeters: Float
    ): Boolean {
        if (!hasLocationPermission(context)) {
            Log.d(TAG, "Location permission not granted, skipping geofence filter (treated as matched).")
            return true
        }

        val location = getLastKnownLocation(context)
        if (location == null) {
            Log.d(TAG, "No cached location available yet; treating geofence as matched to avoid false blocks.")
            return true
        }

        val results = FloatArray(1)
        Location.distanceBetween(
            location.latitude,
            location.longitude,
            targetLat,
            targetLng,
            results
        )
        val distance = results[0]
        val isInside = distance <= radiusMeters
        Log.d(TAG, "Geofence check: target=($targetLat, $targetLng), current=(${location.latitude}, ${location.longitude}), dist=${distance}m, radius=${radiusMeters}m -> inside=$isInside")
        return isInside
    }

    /**
     * One-shot location retrieval for UI rule setup.
     */
    @SuppressLint("MissingPermission")
    fun captureCurrentLocation(context: Context, onResult: (Location?) -> Unit) {
        if (!hasLocationPermission(context)) {
            onResult(null)
            return
        }

        // Try last known first for instant response
        val lastKnown = getLastKnownLocation(context)
        if (lastKnown != null && System.currentTimeMillis() - lastKnown.time < 60_000L) {
            onResult(lastKnown)
            return
        }

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) {
            onResult(lastKnown)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val signal = CancellationSignal()
            try {
                lm.getCurrentLocation(
                    LocationManager.FUSED_PROVIDER,
                    signal,
                    context.mainExecutor,
                    Consumer { location ->
                        onResult(location ?: lastKnown)
                    }
                )
                return
            } catch (_: Exception) {}
        }

        // Fallback for older devices or if getCurrentLocation fails
        val provider = when {
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> LocationManager.PASSIVE_PROVIDER
        }

        try {
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    lm.removeUpdates(this)
                    onResult(location)
                }
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            lm.requestSingleUpdate(provider, listener, context.mainLooper)
        } catch (_: Exception) {
            onResult(lastKnown)
        }
    }
}
