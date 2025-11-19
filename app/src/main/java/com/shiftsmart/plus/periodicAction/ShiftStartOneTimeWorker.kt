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
 * ONE-TIME Worker for Shift Start Backup
 *
 * PURPOSE:
 * ════════
 * Acts as a BACKUP mechanism to start the service when shift begins.
 * This is a ONE-TIME worker scheduled by ShiftRestartAlarmManager to run
 * at the exact shift start time as a fallback if AlarmManager fails.
 *
 * WHEN IT RUNS:
 * ═════════════
 * Scheduled as OneTimeWorkRequest with specific delay until next shift start.
 * Example: If shift starts at 08:00, this runs at 07:50 (10 min before)
 *
 * WHAT IT DOES:
 * ═════════════
 * 1. Check if user is logged in
 * 2. Check if inside shift window (using AlarmReceiver logic)
 * 3. Check if service already running
 * 4. If service NOT running → Start it
 * 5. If service already running → Do nothing
 *
 * DUAL REDUNDANCY:
 * ════════════════
 * This provides redundancy to ensure service starts even on:
 * - Xiaomi/MIUI devices with strict battery optimization
 * - Samsung devices with aggressive Doze mode
 * - Other manufacturers that may delay/cancel AlarmManager
 *
 * NOTE: This is DIFFERENT from ServiceHealthWorker which runs periodically.
 * This worker runs ONCE per shift start as a targeted backup.
 */
class ShiftStartOneTimeWorker(
    context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {

    private val TAG = "ShiftStartWorker"

    override fun doWork(): Result {
        Log.i(TAG, "⏰ One-time shift start backup at ${Utils.getCurrentDateTime()}")

        val user = SharedPref.getInstance(applicationContext)?.getUser()
        if (user == null) {
            Log.e(TAG, "❌ No user found - cannot start service")
            return Result.failure()
        }

        // Check if we're currently within shift window
        val isInsideShift = AlarmReceiver.isInsideShiftWindow(user)

        if (isInsideShift) {
            Log.i(TAG, "✅ Inside shift window - checking service status")

            val isServiceRunning = Utils.isServiceRunning(applicationContext, MyService::class.java)

            if (isServiceRunning) {
                Log.i(TAG, "✅ Service already running - backup not needed")

                // But verify it's actually working by checking last sync time
                val lastSyncTimestamp = SharedPref.getInstance(applicationContext)?.getLastSyncTimestamp() ?: 0L
                if (lastSyncTimestamp > 0L) {
                    val now = System.currentTimeMillis()
                    val minutesSinceLastSync = ((now - lastSyncTimestamp) / (60 * 1000)).toInt()

                    if (minutesSinceLastSync > 15) {
                        Log.w(TAG, "⚠️ Service running but NOT syncing (${minutesSinceLastSync}m ago) - Restarting")
                        restartService()
                    }
                }
            } else {
                Log.i(TAG, "🚨 Service NOT running - Starting via WorkManager BACKUP")
                startService()
            }
        } else {
            Log.i(TAG, "⏭️ Outside shift window - no action needed")
        }

        return Result.success()
    }

    private fun startService() {
        try {
            val serviceIntent = Intent(applicationContext, MyService::class.java).apply {
                action = MyService.ACTION_START
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(serviceIntent)
            } else {
                applicationContext.startService(serviceIntent)
            }

            Log.i(TAG, "✅ MyService started via WorkManager backup")

            // ✅ Ensure alarms are scheduled after service starts
            AlarmReceiver.scheduleNextAlignedAlarm(applicationContext)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error starting MyService", e)
        }
    }

    private fun restartService() {
        try {
            Log.i(TAG, "🔄 Restarting stuck service...")

            // Stop first
            val stopIntent = Intent(applicationContext, MyService::class.java).apply {
                action = MyService.ACTION_STOP
            }
            applicationContext.startService(stopIntent)

            // Wait a moment
            Thread.sleep(1000)

            // Start fresh
            startService()

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error restarting service", e)
        }
    }
}

