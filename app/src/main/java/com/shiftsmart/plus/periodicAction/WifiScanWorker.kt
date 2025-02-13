package com.shiftsmart.plus.periodicAction

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.shiftsmart.plus.models.WifiModel
import com.shiftsmart.plus.utils.Utils


class WifiScanWorker(appContext: Context, workerParams: WorkerParameters) : Worker(appContext, workerParams) {

    private lateinit var wifiManager: WifiManager
    private lateinit var wifiScanReceiver: BroadcastReceiver

    override fun doWork(): Result {
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        // Start Wi-Fi scan
        if (wifiManager.startScan()) {
            Log.i("WifiScanWorker", "Wi-Fi scan started.")
        } else {
            Log.e("WifiScanWorker", "Failed to start Wi-Fi scan.")
            Result.retry()
            return Result.failure()
        }

        // Register the receiver to get the scan results
        wifiScanReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                    val wifiResults = wifiManager.scanResults.map { result ->
                        WifiModel(
                            ssid = result.SSID,
                            bssid = result.BSSID,
                            strength = Utils.rssiToPercentage(result.level)
                        )
                    }
                    // Handle the scan results
                    Log.i("WifiScanWorker", "Wi-Fi scan results: $wifiResults")
                    // Broadcast scan results
                    val scanIntent = Intent("com.example.WIFI_SCAN_RESULTS")
                    scanIntent.putParcelableArrayListExtra("wifiResults", ArrayList(wifiResults))
                    LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(scanIntent)
                }
            }
        }

        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        applicationContext.registerReceiver(wifiScanReceiver, filter)

        return Result.success()
    }

    override fun onStopped() {
        super.onStopped()
        applicationContext.unregisterReceiver(wifiScanReceiver)
    }
}

