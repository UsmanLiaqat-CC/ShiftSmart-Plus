package com.shiftsmart.plus.utils

import android.content.Context
import android.os.Build
import android.util.Log

/**
 * DeviceCompatibilityHelper
 *
 * Detects device-specific issues and provides targeted workarounds
 * for problematic devices like Mara and Mobicel (budget Android devices)
 *
 * MARA PHONES:
 * - Mara X, Mara S, Mara Z series
 * - African-manufactured budget phones
 * - Stock Android with aggressive battery optimization
 * - Common issues: Background tasks killed, alarms throttled
 *
 * MOBICEL PHONES:
 * - South African budget devices
 * - Heavily modified Android
 * - Extremely aggressive power management
 * - Common issues: Services don't restart, alarms don't fire
 */
object DeviceCompatibilityHelper {

    private const val TAG = "DeviceCompat"

    /**
     * Check if device is a Mara phone
     */
    fun isMaraDevice(): Boolean {
        val isMara = Build.MANUFACTURER.equals("mara", ignoreCase = true) ||
                     Build.MODEL.contains("mara", ignoreCase = true) ||
                     Build.BRAND.equals("mara", ignoreCase = true)

        if (isMara) {
            Log.i(TAG, "🔍 Detected Mara device: ${Build.MANUFACTURER} ${Build.MODEL}")
        }
        return isMara
    }

    /**
     * Check if device is a Mobicel phone
     */
    fun isMobicelDevice(): Boolean {
        val isMobicel = Build.MANUFACTURER.equals("mobicel", ignoreCase = true) ||
                        Build.MODEL.contains("mobicel", ignoreCase = true) ||
                        Build.BRAND.equals("mobicel", ignoreCase = true)

        if (isMobicel) {
            Log.i(TAG, "🔍 Detected Mobicel device: ${Build.MANUFACTURER} ${Build.MODEL}")
        }
        return isMobicel
    }

    /**
     * Check if device is a problematic budget device
     * that requires special handling
     */
    fun isProblematicDevice(): Boolean {
        return isMaraDevice() || isMobicelDevice()
    }

    /**
     * Get recommended WorkManager interval for device
     * Problematic devices need more frequent checks
     */
    fun getRecommendedWorkerInterval(): Long {
        return if (isProblematicDevice()) {
            // More frequent checks for problematic devices
            15L // 15 minutes (WorkManager minimum)
        } else {
            15L // Standard for all devices
        }
    }

    /**
     * Get recommended alarm buffer time for device
     * Some devices need extra time before shift start
     */
    fun getAlarmBufferMinutes(): Int {
        return if (isProblematicDevice()) {
            // Start service 2 minutes earlier on problematic devices
            62 // 1 hour 2 minutes before shift
        } else {
            60 // Standard 1 hour buffer
        }
    }

    /**
     * Check if device needs aggressive wake lock strategy
     */
    fun needsAggressiveWakeLock(): Boolean {
        return isProblematicDevice()
    }

    /**
     * Check if device needs WorkManager redundancy enabled
     */
    fun needsWorkerRedundancy(): Boolean {
        return isProblematicDevice()
    }

    /**
     * Get device-specific troubleshooting info
     */
    fun getDeviceTroubleshootingInfo(): String {
        return when {
            isMaraDevice() -> """
                MARA DEVICE DETECTED
                
                Common issues:
                • Background services killed aggressively
                • Alarms may be delayed or skipped
                • App may need to stay in foreground
                
                Required settings:
                1. Settings → Apps → ShiftSmart Plus
                2. Battery → Unrestricted
                3. Mobile data → Allow background data
                4. Keep app in recent apps (don't swipe away)
                
                Extra tip: Lock app in recent apps if available
            """.trimIndent()

            isMobicelDevice() -> """
                MOBICEL DEVICE DETECTED
                
                Common issues:
                • Extremely aggressive power management
                • Background tasks frequently terminated
                • Alarms may not fire reliably
                • Service may not restart after killed
                
                Required settings:
                1. Settings → Apps → ShiftSmart Plus
                2. Battery → Unrestricted (CRITICAL)
                3. Autostart → Enable (if available)
                4. Background data → Allow
                5. NEVER remove app from recent apps
                
                Extra tip: Disable "Battery Saver" mode during shifts
            """.trimIndent()

            else -> """
                DEVICE: ${Build.MANUFACTURER} ${Build.MODEL}
                
                For best results:
                1. Disable battery optimization
                2. Allow background data
                3. Keep app in recent apps during shift
            """.trimIndent()
        }
    }

    /**
     * Log device information for debugging
     */
    fun logDeviceInfo() {
        Log.i(TAG, "========================================")
        Log.i(TAG, "DEVICE INFORMATION")
        Log.i(TAG, "========================================")
        Log.i(TAG, "Manufacturer: ${Build.MANUFACTURER}")
        Log.i(TAG, "Brand: ${Build.BRAND}")
        Log.i(TAG, "Model: ${Build.MODEL}")
        Log.i(TAG, "Device: ${Build.DEVICE}")
        Log.i(TAG, "Android Version: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        Log.i(TAG, "Is Mara: ${isMaraDevice()}")
        Log.i(TAG, "Is Mobicel: ${isMobicelDevice()}")
        Log.i(TAG, "Is Problematic: ${isProblematicDevice()}")
        Log.i(TAG, "Needs Aggressive Wake Lock: ${needsAggressiveWakeLock()}")
        Log.i(TAG, "========================================")
    }

    /**
     * Show device-specific warning to user if on problematic device
     */
    fun shouldShowDeviceWarning(): Boolean {
        return isProblematicDevice()
    }
}

