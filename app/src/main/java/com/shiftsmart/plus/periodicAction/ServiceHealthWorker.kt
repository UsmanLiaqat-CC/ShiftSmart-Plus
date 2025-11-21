package com.shiftsmart.plus.periodicAction

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.shiftsmart.plus.services.MyService
import com.shiftsmart.plus.utils.SharedPref
import com.shiftsmart.plus.utils.Utils

/**
 * ⏰ PERIODIC SERVICE HEALTH MONITOR (Every 15 Minutes)
 *
 * PURPOSE:
 * ════════
 * Acts as a watchdog to ensure MyService stays running during shift hours.
 * Runs every 15 minutes to detect and recover from service failures caused by:
 * - Device entering Doze mode
 * - Manufacturer battery optimization killing the service
 * - Service crashes or unexpected stops
 *
 * APPROACH:
 * ═════════
 * 1. Check if current time is within shift (including overnight shifts)
 * 2. Verify if service is actually running (not just isServiceRunning flag)
 * 3. If service should be running but isn't → Restart it
 * 4. Use multiple detection methods to avoid false positives
 *
 * DOZE MODE HANDLING:
 * ══════════════════
 * - Uses WorkManager which has better Doze survival than AlarmManager
 * - Runs as PeriodicWorkRequest with 15-minute intervals
 * - Flex interval allows system to batch work for battery efficiency
 * - setRequiredNetworkType(NOT_REQUIRED) to run even without internet
 *
 * WHY 15 MINUTES:
 * ══════════════
 * - Minimum interval for PeriodicWorkRequest is 15 minutes (Android restriction)
 * - Frequent enough to catch service failures quickly
 * - Infrequent enough to not drain battery
 */
class ServiceHealthWorker(
    context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {

    private val TAG = "ServiceHealthWorker"

    override fun doWork(): Result {
        Log.i(TAG, "⏰ 15-min health check at ${Utils.getCurrentDateTime()}")

        val user = SharedPref.getInstance(applicationContext)?.getUser()
        if (user == null) {
            Log.e(TAG, "❌ No user found - cannot check shift status")
            return Result.failure()
        }

        // ✅ Check if we're currently within shift window (handles overnight shifts)
        val isInsideShift = AlarmReceiver.isInsideShiftWindow(user)

        if (isInsideShift) {
            Log.i(TAG, "✅ Currently INSIDE shift window")

            // ✅ Check if service is actually running (robust detection)
            val isServiceActuallyRunning = isServiceReallyRunning()

            if (isServiceActuallyRunning) {
                Log.i(TAG, "✅ Service confirmed running - all good")
            } else {
                Log.w(TAG, "🚨 Service NOT running but should be - Restarting service!")
                restartService()
            }
        } else {
            Log.i(TAG, "⏭️ Currently OUTSIDE shift window - no action needed")

            // Optional: Stop service if it's somehow still running outside shift
            val isServiceRunning = Utils.isServiceRunning(applicationContext, MyService::class.java)
            if (isServiceRunning) {
                Log.w(TAG, "⚠️ Service running outside shift - stopping it")
                stopService()
            }
        }

        return Result.success()
    }

    /**
     * Robust service detection that checks multiple indicators.
     * This is more reliable than just isServiceRunning() which can return
     * false positives on some devices (especially in Doze mode).
     */



    private fun isServiceReallyRunning(): Boolean {
        // Method 1: Standard Android API check
        val apiCheck = Utils.isServiceRunning(applicationContext, MyService::class.java)

        // Method 2: Check last sync timestamp (if service is running, it should be updating)
        val lastSyncTimestamp =
            SharedPref.getInstance(applicationContext)?.getLastSyncTimestamp() ?: 0L
        val now = System.currentTimeMillis()
        val minutesSinceLastSync = ((now - lastSyncTimestamp) / (60 * 1000)).toInt()

        // If last sync was within 5 minutes, service is likely running
        // Reduced from 20 to 5 to detect failures faster, but monitor for false positives in Doze mode
        val recentSyncCheck = lastSyncTimestamp > 0L && minutesSinceLastSync < 5

        Log.i(
            TAG,
            "   🔍 Service check: API=$apiCheck, RecentSync=$recentSyncCheck (${minutesSinceLastSync}m ago)"
        )

        // Service is considered running if EITHER check passes
        // This reduces false negatives in Doze mode
        return apiCheck || recentSyncCheck
    }

    /**
     * Restart the service by stopping (if running) and starting fresh.
     * This is more reliable than just starting, as it clears any stuck state.
     */
    private fun restartService() {
        try {
            Log.i(TAG, "🔄 Restarting service...")

            // Step 1: Stop existing service (if any)
            try {
                val stopIntent = Intent(applicationContext, MyService::class.java).apply {
                    action = MyService.ACTION_STOP
                }
                applicationContext.startService(stopIntent)
                Log.i(TAG, "   📤 Stop signal sent")

                // Small delay to let service stop cleanly
                Thread.sleep(1000)
            } catch (e: Exception) {
                Log.w(TAG, "   ⚠️ Stop failed (may not be running): ${e.message}")
            }

            // Step 2: Start service fresh
            val startIntent = Intent(applicationContext, MyService::class.java).apply {
                action = MyService.ACTION_START
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(startIntent)
            } else {
                applicationContext.startService(startIntent)
            }

            Log.i(TAG, "   ✅ Service restart initiated")

            // Step 3: Reschedule alarms to ensure they're active
            AlarmReceiver.scheduleNextAlignedAlarm(applicationContext)

        } catch (e: Exception) {
            Log.e(TAG, "   ❌ Error restarting service", e)
        }
    }

    /**
     * Stop the service (used when service is running outside shift hours)
     */
    private fun stopService() {
        try {
            val stopIntent = Intent(applicationContext, MyService::class.java).apply {
                action = MyService.ACTION_STOP
            }
            applicationContext.startService(stopIntent)
            Log.i(TAG, "✅ Service stop signal sent")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error stopping service", e)
        }
    }
}

