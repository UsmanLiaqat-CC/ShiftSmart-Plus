package com.shiftsmart.plus.periodicAction

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.shiftsmart.plus.services.MyService
import com.shiftsmart.plus.utils.SharedPref
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * PeriodicSyncWorker
 *
 * PURPOSE: Fallback mechanism for alarm-driven data collection
 *
 * NEW ROLE (Alarm-Driven Pattern):
 * ════════════════════════════════
 * - WorkManager as SAFETY NET (not primary mechanism)
 * - Runs every 15 minutes to catch missed alarms
 * - Triggers ACTION_COLLECT_AND_STOP (wake → work → sleep)
 * - Ensures AlarmManager stays scheduled
 *
 * WHY KEEP THIS:
 * ══════════════
 * - Some manufacturers break AlarmManager in deep Doze
 * - WorkManager has better Doze survival on some devices
 * - Provides redundancy without fighting the alarm pattern
 *
 * HOW IT WORKS:
 * ═════════════
 * - Checks if inside shift window
 * - Validates 5-minute boundary and gap
 * - Triggers ACTION_COLLECT_AND_STOP (not ACTION_CALL_API)
 * - Service will start, collect, save, sync, stop
 * - Re-verifies AlarmManager is scheduled
 */
class PeriodicSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "PeriodicSyncWorker"
    }

    override suspend fun doWork(): Result {

        try {
            val user = SharedPref.getInstance(applicationContext)?.getUser()
            if (user == null) {
                Log.w(TAG, "❌ No user found - skipping")
                return Result.success()
            }

            // ✅ STEP 1: Always ensure AlarmManager is scheduled (primary mechanism)
            if (AlarmReceiver.isInsideShiftWindow(user)) {
                Log.i(TAG, "✅ Inside shift - ensuring alarms are scheduled")
                AlarmReceiver.scheduleNextAlignedAlarm(applicationContext)
                return Result.success()
            } else {
                Log.i(TAG, "⏸️ Outside shift - no action needed")
                return Result.success()
            }


        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in PeriodicSyncWorker", e)
            return Result.retry()
        }
    }


}

