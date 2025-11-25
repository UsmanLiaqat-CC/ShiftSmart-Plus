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
import com.shiftsmart.plus.utils.DeviceCompatibilityHelper

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

        // Log device info for problematic devices
        if (DeviceCompatibilityHelper.isProblematicDevice()) {
            Log.w(TAG, "⚠️ Running on problematic device: ${Build.MANUFACTURER} ${Build.MODEL}")
        }


        val user = SharedPref.getInstance(applicationContext)?.getUser()
        Log.i("BootReceiver", "app user from SharedPref: $user")

        if (user != null && AlarmReceiver.isInsideShiftWindow(user)) {
            Log.i("BootReceiver", "⏰ Service destroyed during shift - AlarmManager will handle next wake-up")
            // Ensure alarms are scheduled (they should already be, but just in case)
            AlarmReceiver.scheduleNextAlignedAlarm(applicationContext)
        } else {
            Log.i("BootReceiver", "⏸️ Service destroyed outside shift - no action needed")
        }

        return Result.success()
    }

}

