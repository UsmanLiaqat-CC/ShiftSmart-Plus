package com.shiftsmart.plus.utils

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.shiftsmart.plus.periodicAction.WifiScanWorker
import javax.inject.Inject

class WifiScanner @Inject constructor(private val context: Context) {

    private val wifiManager: WifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val wifiReceiver: WifiReceiver = WifiReceiver()
    private var callback: ((List<ScanResult>) -> Unit)? = null

    fun isWifiEnabled(): Boolean {
        return wifiManager.isWifiEnabled
    }

    // Check for permissions and start the scan
    fun scanWifiNetworks(completion: (List<ScanResult>) -> Unit) {
        callback = completion

        // Ensure location permission is granted
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // Request permission if not granted
            return
        }
        registerWifiReceiver()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // For Android 8.0+, use WorkManager to handle WiFi scan in the background
            startWifiScanWork()
        } else {
            // For older Android versions, use BroadcastReceiver
            registerWifiReceiver()
            wifiManager.startScan()
        }
    }
    // Register the WifiReceiver for scanning results
    private fun registerWifiReceiver() {
        val intentFilter = IntentFilter()
        intentFilter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        context.registerReceiver(wifiReceiver, intentFilter)
    }

    // Unregister receiver after receiving scan results
    private fun unregisterWifiReceiver() {
        try {
            context.unregisterReceiver(wifiReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver not registered, safe to ignore
        }
    }
    // Start WorkManager to handle background WiFi scan task
    @SuppressLint("MissingPermission")
    private fun startWifiScanWork() {
        val workRequest = OneTimeWorkRequestBuilder<WifiScanWorker>().build()
        WorkManager.getInstance(context).enqueue(workRequest)

        // Observe work status to get scan results
        WorkManager.getInstance(context).getWorkInfoByIdLiveData(workRequest.id).observeForever { workInfo ->
            if (workInfo?.state == WorkInfo.State.SUCCEEDED) {
                // Fetch WiFi scan results here
                val wifiScanResults = wifiManager.scanResults
                callback?.invoke(wifiScanResults)
            }
        }
    }

    // Unregister the receiver when it's no longer needed
    fun stopWifiScan() {
        unregisterWifiReceiver()
    }

    // WifiReceiver class that gets WiFi scan results
    inner class WifiReceiver : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                val wifiScanResults = wifiManager.scanResults
                callback?.invoke(wifiScanResults)
                unregisterWifiReceiver() // Unregister receiver after receiving results
            }
        }
    }
}
