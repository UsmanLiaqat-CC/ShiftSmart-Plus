package com.shiftsmart.plus.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

class LocationHelper(private val context: Context) {

    var lastLocation: LatLng = LatLng(0.0, 0.0)

    suspend fun fetchFreshLocation(): LatLng = withContext(Dispatchers.Main) {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // If permissions are not granted, try to return last known location without throwing
        if (!hasLocationPermissions()) {
            Log.w("LocationHelper", "Missing location permissions. Attempting last known location fallback.")
            val lastKnown = getLastKnownLocation(locationManager)
            return@withContext lastKnown?.let { LatLng(it.latitude, it.longitude) } ?: LatLng(0.0, 0.0)
        }

        val isResumed = AtomicBoolean(false)

        suspendCancellableCoroutine { continuation ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (isResumed.compareAndSet(false, true)) {
                        locationManager.removeUpdates(this)
                        continuation.resume(location)
                    }
                }

                override fun onProviderDisabled(provider: String) {
                    if (isResumed.compareAndSet(false, true)) {
                        continuation.resume(null)
                    }
                }

                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
            }

            try {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    0L,
                    0f,
                    listener,
                    Looper.getMainLooper()
                )
            } catch (e: SecurityException) {
                Log.e("LocationHelper", "SecurityException while requesting updates: ${e.message}")
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }

            continuation.invokeOnCancellation { locationManager.removeUpdates(listener) }

            CoroutineScope(Dispatchers.IO).launch {
                delay(10000)
                if (isResumed.compareAndSet(false, true)) {
                    val lastKnown = getLastKnownLocation(locationManager)
                    withContext(Dispatchers.Main) {
                        locationManager.removeUpdates(listener)
                        continuation.resume(lastKnown)
                    }
                }
            }
        }?.let {
            lastLocation = LatLng(it.latitude, it.longitude)
            lastLocation
        } ?: LatLng(0.0, 0.0)
    }


/*
    private fun getLastKnownLocation(locationManager: LocationManager): Location? {
        return if (hasLocationPermissions()) {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } else null
    }
*/

    private fun getLastKnownLocation(locationManager: LocationManager): Location? {
        return try {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (e: SecurityException) {
            Log.e("LocationHelper", "SecurityException in getLastKnownLocation: ${e.message}")
            null
        }
    }

    fun hasLocationPermissions(): Boolean {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

}
