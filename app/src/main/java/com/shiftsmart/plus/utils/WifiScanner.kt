package com.shiftsmart.plus.utils

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.shiftsmart.plus.models.WifiModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

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

        // Check permissions
        if (!hasWifiPermissions()) {
            Log.w(TAG, "⚠️ WiFi scan permission not granted - returning empty list")
            return emptyList()
        }

        return try {
            // Get scan results based on Android version
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                    // Android 10+ (API 29+): Scan throttling, use cached results more aggressively
                    getWifiListModern(maxWaitTimeMs)
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                    // Android 6-9 (API 23-28): Standard scan with permissions
                    getWifiListLegacy(maxWaitTimeMs)
                }
                else -> {
                    // Android 5.x and below (API < 23): Direct scan
                    getWifiListOld()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error scanning WiFi: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Modern WiFi scanning for Android 10+ (API 29+)
     * Scan throttling applies: max 4 scans per 2 minutes
     */
    @SuppressLint("MissingPermission")
    private suspend fun getWifiListModern(maxWaitTimeMs: Long): List<WifiModel> {
        Log.d(TAG, "📱 Using modern WiFi scan (Android 10+)")

        // Try to get cached results first (more reliable on Android 10+)
        val cachedResults = getCachedWifiResults()
        if (cachedResults.isNotEmpty()) {
            Log.i(TAG, "✅ Using cached WiFi results: ${cachedResults.size} networks")
            return cachedResults
        }

        // Attempt fresh scan (may be throttled)
        val scanStarted = wifiManager.startScan()
        if (!scanStarted) {
            Log.w(TAG, "⚠️ WiFi scan throttled or failed - using last available results")
            return getCachedWifiResults()
        }

        // Wait for scan to complete
        Log.d(TAG, "⏳ Waiting for scan results (max ${maxWaitTimeMs}ms)...")
        val result = withTimeoutOrNull(maxWaitTimeMs) {
            // Poll for new results
            var attempts = 0
            val maxAttempts = (maxWaitTimeMs / 500).toInt()

            while (attempts < maxAttempts) {
                delay(500)
                val results = getCachedWifiResults()
                if (results.isNotEmpty()) {
                    Log.i(TAG, "✅ Fresh scan results received: ${results.size} networks")
                    return@withTimeoutOrNull results
                }
                attempts++
            }
            null
        }

        return result ?: getCachedWifiResults()
    }

    /**
     * Legacy WiFi scanning for Android 6-9 (API 23-28)
     */
    @SuppressLint("MissingPermission")
    private suspend fun getWifiListLegacy(maxWaitTimeMs: Long): List<WifiModel> {
        Log.d(TAG, "📱 Using legacy WiFi scan (Android 6-9)")

        // Start scan
        val scanStarted = wifiManager.startScan()
        if (!scanStarted) {
            Log.w(TAG, "⚠️ Failed to start WiFi scan - using cached results")
            return getCachedWifiResults()
        }

        // Wait for results
        Log.d(TAG, "⏳ Waiting for scan results (max ${maxWaitTimeMs}ms)...")
        delay(3000) // Fixed 3 second wait for scan to complete

        return getCachedWifiResults()
    }

    /**
     * Old WiFi scanning for Android 5.x and below (API < 23)
     */
    @SuppressLint("MissingPermission")
    private fun getWifiListOld(): List<WifiModel> {
        Log.d(TAG, "📱 Using old WiFi scan (Android 5.x)")

        // Direct scan (no special permissions needed)
        wifiManager.startScan()

        // Small delay for scan
        Thread.sleep(2000)

        return getCachedWifiResults()
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
                }
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
     * Get WiFi list synchronously (for non-coroutine contexts)
     * Note: This may block the thread, use sparingly
     */
    @SuppressLint("MissingPermission")
    fun getWifiListSync(): List<WifiModel> {
        Log.d(TAG, "🔍 Getting WiFi list synchronously...")

        if (!wifiManager.isWifiEnabled) {
            Log.w(TAG, "⚠️ WiFi is disabled")
            return emptyList()
        }

        if (!hasWifiPermissions()) {
            Log.w(TAG, "⚠️ WiFi permissions not granted")
            return emptyList()
        }

        // Try to trigger a scan (may fail due to throttling on Android 10+)
        try {
            wifiManager.startScan()
            // Short wait
            Thread.sleep(2000)
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Scan trigger failed: ${e.message}")
        }

        // Return cached results
        return getCachedWifiResults()
    }

    /**
     * Check if WiFi is enabled
     */
    fun isWifiEnabled(): Boolean {
        return wifiManager.isWifiEnabled
    }
}

