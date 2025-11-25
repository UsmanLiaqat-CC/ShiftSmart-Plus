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

        if (user != null && AlarmReceiver.isInsideShiftWindow(user)) {
            Log.i("BootReceiver", "⏰ Service destroyed during shift - AlarmManager will handle next wake-up")
            // Ensure alarms are scheduled (they should already be, but just in case)
            AlarmReceiver.scheduleNextAlignedAlarm(applicationContext)
            return Result.success()
        } else {
            Log.i("BootReceiver", "⏸️ Service destroyed outside shift - no action needed")

            return Result.failure()
        }


    }

}

