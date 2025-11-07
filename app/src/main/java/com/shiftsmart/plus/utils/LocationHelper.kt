package com.shiftsmart.plus.utils

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.model.LatLng
import java.util.concurrent.atomic.AtomicBoolean

/*
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
*/
class LocationHelper(private val context: Context) {

    var lastLocation: LatLng = LatLng(0.0, 0.0)

    private val fusedClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }


    // for version 8
//    suspend fun fetchFreshLocation(): LatLng = withContext(Dispatchers.Main) {
//        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
//
//        if (!hasLocationPermissions()) {
//            Log.w("LocationHelper", "Missing permissions → Using last known location fallback")
//            return@withContext getBestAvailableLocation()  // ✅ fallback
//        }
//
//        val isResumed = AtomicBoolean(false)
//
//        suspendCancellableCoroutine { continuation ->
//            val listener = object : LocationListener {
//                override fun onLocationChanged(location: Location) {
//                    if (isResumed.compareAndSet(false, true)) {
//                        locationManager.removeUpdates(this)
//                        continuation.resume(location)
//                    }
//                }
//
//                override fun onProviderDisabled(provider: String) {
//                    if (isResumed.compareAndSet(false, true)) {
//                        continuation.resume(null)
//                    }
//                }
//            }
//
//            try {
//                locationManager.requestLocationUpdates(
//                    LocationManager.NETWORK_PROVIDER,
//                    0L,
//                    0f,
//                    listener,
//                    Looper.getMainLooper()
//                )
//            } catch (e: SecurityException) {
//                continuation.resume(null)
//            }
//
//            continuation.invokeOnCancellation {
//                locationManager.removeUpdates(listener)
//            }
//
//            // Timeout 10s → fallback
//            CoroutineScope(Dispatchers.IO).launch {
//                delay(10000)
//                if (isResumed.compareAndSet(false, true)) {
//                    val fallback = getBestAvailableLocation()  // ✅ fallback call
//                    withContext(Dispatchers.Main) {
//                        locationManager.removeUpdates(listener)
//                        continuation.resume(fallback?.let { latLng ->
//                            Location(LocationManager.GPS_PROVIDER).apply {
//                                latitude = latLng.latitude
//                                longitude = latLng.longitude
//                            }
//                        })
//
//                    }
//                }
//            }
//        }?.let { location ->
//            lastLocation = LatLng(location.latitude, location.longitude)
//            lastLocation
//        } ?: run {
//            Log.w("LocationHelper", "No live location — using fallback")
//            getBestAvailableLocation()
//        }
//    }


    /////////////////////////
// Add callback-based method alongside the suspend function
    @SuppressLint("MissingPermission")
    fun fetchFreshLocation(callback: (latLng: LatLng?, error: String?) -> Unit) {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        if (!hasLocationPermissions()) {
            Log.w("LocationHelper", "Missing location permissions. Attempting last known location fallback.")
            val lastKnown = getBestLastKnownLocation(locationManager)
            callback(lastKnown, "No permissions")
            return
        }

        val isResumed = AtomicBoolean(false)
        val timeoutHandler = android.os.Handler(Looper.getMainLooper())

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (isResumed.compareAndSet(false, true)) {
                    timeoutHandler.removeCallbacksAndMessages(null)
                    locationManager.removeUpdates(this)
                    lastLocation = LatLng(location.latitude, location.longitude)
                    Log.i("LocationHelper", "Fresh location obtained: ${lastLocation.latitude}, ${lastLocation.longitude}")
                    callback(lastLocation, null)
                }
            }

            override fun onProviderDisabled(provider: String) {
                if (isResumed.compareAndSet(false, true)) {
                    timeoutHandler.removeCallbacksAndMessages(null)
                    locationManager.removeUpdates(this)
                    Log.w("LocationHelper", "Provider disabled, fetching last known location")
                    val result = getBestLastKnownLocation(locationManager)
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
                    Log.w("LocationHelper", "Location fetch timeout, fetching last known location")
                    val result = getBestLastKnownLocation(locationManager)
                    callback(result, null) // No error since we got a fallback location
                }
            }, 10000)

        } catch (e: SecurityException) {
            Log.e("LocationHelper", "SecurityException while requesting updates: ${e.message}")
            val fallback = getBestLastKnownLocation(locationManager)
            callback(fallback, e.message)
        }
    }

    /**
     * Get the best available last known location from multiple providers
     * Falls back through GPS -> Network -> Fused -> last stored location
     */
    @SuppressLint("MissingPermission")
    private fun getBestLastKnownLocation(locationManager: LocationManager): LatLng {
        if (!hasLocationPermissions()) {
            Log.w("LocationHelper", "No permissions, returning last stored location")
            return lastLocation
        }

        // Try GPS first (most accurate)
        val gpsLocation = try {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        } catch (e: SecurityException) {
            Log.e("LocationHelper", "GPS SecurityException: ${e.message}")
            null
        }

        // Try Network provider
        val networkLocation = try {
            locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (e: SecurityException) {
            Log.e("LocationHelper", "Network SecurityException: ${e.message}")
            null
        }

        // Try Fused Location Provider (Google Play Services)
        val fusedLocation = try {
            // This is a synchronous call to get last location
            var location: Location? = null
            fusedClient.lastLocation.addOnSuccessListener { loc ->
                location = loc
            }
            location
        } catch (e: Exception) {
            Log.e("LocationHelper", "Fused location error: ${e.message}")
            null
        }

        // Choose the most recent and accurate location
        val bestLocation = listOfNotNull(gpsLocation, networkLocation, fusedLocation)
            .maxByOrNull { it.time } // Get most recent

        return if (bestLocation != null) {
            lastLocation = LatLng(bestLocation.latitude, bestLocation.longitude)
            Log.i("LocationHelper", "Using last known location: ${lastLocation.latitude}, ${lastLocation.longitude}")
            lastLocation
        } else {
            Log.w("LocationHelper", "No last known location available, using previous stored location")
            lastLocation
        }
    }


    fun hasLocationPermissions(): Boolean {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
}
