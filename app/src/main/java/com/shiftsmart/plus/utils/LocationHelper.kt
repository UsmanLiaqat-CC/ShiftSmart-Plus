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

            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                0L,
                0f,
                listener,
                Looper.getMainLooper()
            )

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
        return if (hasLocationPermissions()) {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } else null
    }

    private fun hasLocationPermissions(): Boolean {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
    // Method to check if the required permissions are granted
    fun checkLocationPermissions(): Boolean {
        val fineLocationPermission = ActivityCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        )
        val coarseLocationPermission = ActivityCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        )

        // Check for Android 14+ (if needed)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            fineLocationPermission == PackageManager.PERMISSION_GRANTED &&
                    coarseLocationPermission == PackageManager.PERMISSION_GRANTED
        } else {
            // For Android versions below 13 (including 13 itself)
            fineLocationPermission == PackageManager.PERMISSION_GRANTED &&
                    coarseLocationPermission == PackageManager.PERMISSION_GRANTED
        }

    }
}
