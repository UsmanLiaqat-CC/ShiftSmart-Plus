package com.shiftsmart.plus.utils

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.shiftsmart.plus.models.WifiModel
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.suspendCoroutine

/**
 * WiFi Scanner utility class that works across all Android versions
 * Provides synchronous WiFi scan results with proper error handling
 */
class WifiScanner(private val context: Context) {

    private val TAG = "WifiScanner"
    private val wifiManager: WifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    /**
     * Get fresh WiFi scan results
     * This method handles different Android versions and permission requirements
     *
     * @param maxWaitTimeMs Maximum time to wait for scan results (default 5 seconds)
     * @return List of WifiModel containing nearby WiFi networks
     */
    @SuppressLint("MissingPermission")
    suspend fun getFreshWifiList(maxWaitTimeMs: Long = 5000): List<WifiModel> {
        Log.d(TAG, "🔍 Starting fresh WiFi scan...")

        // Check if WiFi is enabled
        if (!wifiManager.isWifiEnabled) {
            Log.w(TAG, "⚠️ WiFi is disabled - returning empty list")
            return emptyList()
        }

        // Attempt scan regardless of location permission.
        // On Android 10+ the OS requires ACCESS_FINE_LOCATION to read scan results;
        // getCachedWifiResults() already catches SecurityException and returns emptyList() in that case.
        // On older Android versions the scan may succeed even without location permission.
        if (!hasWifiPermissions()) {
            Log.w(TAG, "⚠️ WiFi scan permission not granted - attempting cached results anyway")
            return getCachedWifiResults() ?: emptyList()
        }

        return try {
            val result = withTimeoutOrNull(maxWaitTimeMs) { performWifiScan() } ?: (getCachedWifiResults() ?: emptyList())
            result
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error scanning WiFi: ${e.message}", e)
            getCachedWifiResults()
        }
    }

    /**
     * Get cached WiFi scan results from WifiManager
     * This works on all Android versions
     */
    @SuppressLint("MissingPermission")
    private fun getCachedWifiResults(): List<WifiModel> {
        return try {
            val scanResults = wifiManager.scanResults
            if (scanResults.isNullOrEmpty()) {
                Log.d(TAG, "📭 No WiFi scan results available")
                emptyList()
            } else {
                val wifiList = scanResults.map { scanResult ->
                    val rssi = scanResult.level
                    val strengthPercentage = rssiToPercentage(rssi)

                    WifiModel(
                        ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            scanResult.wifiSsid?.toString() ?: "Unknown"
                        } else {
                            @Suppress("DEPRECATION")
                            scanResult.SSID
                        },
                        bssid = scanResult.BSSID,
                        strength = strengthPercentage,
                    )
                }.sortedByDescending { it.strength }
//                if (wifiList.isNotEmpty()) {
//                    lastWifiList = wifiList
//                }
                Log.d(TAG, "📶 Retrieved ${wifiList.size} WiFi networks")
                wifiList
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ Security exception accessing WiFi results: ${e.message}")
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error getting cached WiFi results: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Convert RSSI (signal strength in dBm) to percentage (0-100%)
     * @param rssi Signal strength in dBm (typically -30 to -90)
     * @return Signal strength as percentage (0-100)
     */
    private fun rssiToPercentage(rssi: Int): Int {
        val minRssi = -90  // Very weak signal
        val maxRssi = -30  // Very strong signal

        // Clamp the RSSI value to the defined range
        val clampedRssi = rssi.coerceIn(minRssi, maxRssi)

        // Convert to percentage: -90 dBm = 0%, -30 dBm = 100%
        return ((clampedRssi - minRssi) * 100 / (maxRssi - minRssi)).coerceIn(0, 100)
    }

    /**
     * Check if app has required WiFi scanning permissions
     */
    private fun hasWifiPermissions(): Boolean {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                // Android 10+: Requires ACCESS_FINE_LOCATION
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                // Android 6-9: Requires ACCESS_COARSE_LOCATION or ACCESS_FINE_LOCATION
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
            }
            else -> {
                // Android 5.x and below: No location permission needed
                true
            }
        }
    }


    /**
     * Check if WiFi is enabled
     */
    fun isWifiEnabled(): Boolean {
        return wifiManager.isWifiEnabled
    }

    /**
     * Perform WiFi scan using BroadcastReceiver for reliable results
     */
    @SuppressLint("MissingPermission")
    private suspend fun performWifiScan(): List<WifiModel> {
        return suspendCoroutine { continuation ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                        try {
                            context?.unregisterReceiver(this)
                            val results = getCachedWifiResults()
                            Log.i(TAG, "✅ Scan results received: ${results.size} networks")
                            continuation.resume(results)
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Error in receiver: ${e.message}")
                            continuation.resume(getCachedWifiResults())
                        }
                    }
                }
            }

            try {
                context.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
                val scanStarted = wifiManager.startScan()
                if (!scanStarted) {
                    Log.w(TAG, "⚠️ Scan not started, using cached results")
                    context.unregisterReceiver(receiver)
                    continuation.resume( getCachedWifiResults())
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error starting scan: ${e.message}")
                try {
                    context.unregisterReceiver(receiver)
                } catch (e2: Exception) {
                    // Ignore
                }
                continuation.resume(getCachedWifiResults())
            }
        }
    }
}
