package com.shiftsmart.plus.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.Priority
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

// In LocationHelper.kt

    // Add this NEW method (keep your existing suspend fun too)
    fun fetchFreshLocation(callback: (latLng: LatLng?, error: String?) -> Unit) {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        if (!hasLocationPermissions()) {
            Log.w("LocationHelper", "Missing location permissions. Attempting last known location fallback.")
            val lastKnown = getLastKnownLocation(locationManager)
            callback(lastKnown?.let { LatLng(it.latitude, it.longitude) } ?: LatLng(0.0, 0.0), "No permissions")
            return
        }

        val isResumed = AtomicBoolean(false)
        val timeoutHandler = Handler(Looper.getMainLooper())

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (isResumed.compareAndSet(false, true)) {
                    timeoutHandler.removeCallbacksAndMessages(null)
                    locationManager.removeUpdates(this)
                    lastLocation = LatLng(location.latitude, location.longitude)
                    callback(lastLocation, null)
                }
            }

            override fun onProviderDisabled(provider: String) {
                if (isResumed.compareAndSet(false, true)) {
                    timeoutHandler.removeCallbacksAndMessages(null)
                    locationManager.removeUpdates(this)
                    val lastKnown = getLastKnownLocation(locationManager)
                    val result = lastKnown?.let { LatLng(it.latitude, it.longitude) } ?: lastLocation
                    callback(result, "Provider disabled")
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

            // 10 second timeout
            timeoutHandler.postDelayed({
                if (isResumed.compareAndSet(false, true)) {
                    locationManager.removeUpdates(listener)
                    val lastKnown = getLastKnownLocation(locationManager)
                    val result = lastKnown?.let { LatLng(it.latitude, it.longitude) } ?: lastLocation
                    Log.w("LocationHelper", "Location fetch timeout, using fallback")
                    callback(result, "Timeout")
                }
            }, 10000)

        } catch (e: SecurityException) {
            Log.e("LocationHelper", "SecurityException while requesting updates: ${e.message}")
            callback(lastLocation, e.message)
        }
    }

    // for version app 8
/*
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
*/


    // In LocationHelper.kt

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
