package com.shiftsmart.plus.service
import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Created by Usman Liaqat on 28,Jan,2025
 * usmanliaqat@codecoytechnologies.com,
 * CodeCoy Technologies,
 * Lahore, Pakistan.
 */

@AndroidEntryPoint
class LocationTrack @Inject constructor(private val mContext: Context) : Service() {

    var loc: Location? = null


    companion object {
        private const val MIN_DISTANCE_CHANGE_FOR_UPDATES: Long = 0
        private const val MIN_TIME_BW_UPDATES: Long = 1000
    }

    private var locationListener: LocationListener? = null

    private lateinit var locationManager: LocationManager

    @SuppressLint("MissingPermission")
    fun getLocation(locationManager: LocationManager, callback: (Location?) -> Unit) {

        this.locationManager = locationManager

        if (ActivityCompat.checkSelfPermission(mContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            && ActivityCompat.checkSelfPermission(mContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) {

            locationListener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    loc = location
                    callback(location)
                    locationManager.removeUpdates(this)
                    Log.i("TAG", "onLocationChanged: ${location.latitude}, ${location.longitude}")
                }

                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                MIN_TIME_BW_UPDATES,
                MIN_DISTANCE_CHANGE_FOR_UPDATES.toFloat(),
                locationListener!!
            )
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                MIN_TIME_BW_UPDATES,
                MIN_DISTANCE_CHANGE_FOR_UPDATES.toFloat(),
                locationListener!!
            )


        }
        else {
            callback(null)
        }
    }



    // Method to check if the required permissions are granted
    fun checkLocationPermissions(): Boolean {
        val fineLocationPermission = ActivityCompat.checkSelfPermission(
            mContext, Manifest.permission.ACCESS_FINE_LOCATION
        )
        val coarseLocationPermission = ActivityCompat.checkSelfPermission(
            mContext, Manifest.permission.ACCESS_COARSE_LOCATION
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

    fun stopListener() {
        locationListener?.let {
            locationManager.removeUpdates(it)
        }
    }

    override fun onBind(p0: Intent?): IBinder? {
        return null
    }
}
