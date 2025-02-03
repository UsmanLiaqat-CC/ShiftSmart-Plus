package com.shiftsmart.plus.utils

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.app.ActivityCompat
import javax.inject.Inject


class WifiScanner @Inject constructor(private val context: Context) {

    private  var wifiManager: WifiManager
    private  var wifiReceiver: WifiReceiver
    private var callback: ((List<ScanResult>) -> Unit)? = null
    init {
        wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiReceiver = WifiReceiver()
    }

    fun isWifiEnabled(): Boolean {
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return wifiManager.isWifiEnabled
    }
    fun scanWifiNetworks(completion: (List<ScanResult>) -> Unit) {
        wifiManager.scanResults
            .clear()
        callback = completion
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            // Request permission if not granted
            return
        }
        registerWifiReceiver()
        wifiManager.startScan()
    }

    private fun registerWifiReceiver() {
        val intentFilter = IntentFilter()
        intentFilter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        context.registerReceiver(wifiReceiver, intentFilter)
    }

    fun unregisterWifiReceiver() {
        try {
            context.unregisterReceiver(wifiReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver not registered
        }
    }

    inner class WifiReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                val wifiScanResults = wifiManager.scanResults
                callback?.invoke(wifiScanResults)
                unregisterWifiReceiver() // Unregister receiver after receiving results
            }
        }
    }
}
