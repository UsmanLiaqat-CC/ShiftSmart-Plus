package com.shiftsmart.plus.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager

class GpsStatusMonitor(private val context: Context) {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    // Use a listener interface for callback
    interface GpsStatusListener {
        fun onGpsStatusChanged(enabled: Boolean)
    }

    private var listener: GpsStatusListener? = null

    fun setListener(listener: GpsStatusListener) {
        this.listener = listener
        checkGpsStatus()
    }

    fun removeListener() {
        this.listener = null
    }

    private val gpsSwitchStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LocationManager.PROVIDERS_CHANGED_ACTION) {
                checkGpsStatus()
            }
        }
    }

    fun startMonitoring() {
        val filter = IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION)
        context.registerReceiver(gpsSwitchStateReceiver, filter)
        checkGpsStatus()
    }

    fun stopMonitoring() {
        try {
            context.unregisterReceiver(gpsSwitchStateReceiver)
        } catch (e: Exception) {
            // Receiver wasn't registered, ignore
        }
    }

    private fun checkGpsStatus() {
        val isEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        listener?.onGpsStatusChanged(isEnabled)
    }
}
