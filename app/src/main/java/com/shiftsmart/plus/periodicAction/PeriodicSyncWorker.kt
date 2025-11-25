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
 * PURPOSE: Fallback mechanism when AlarmManager gets restricted by Doze Mode
 *
 * CRITICAL FOR ANDROID 10:
 * - WorkManager survives Doze Mode restrictions
 * - Guaranteed execution even when app is in standby
 * - Provides redundancy when exact alarms get delayed
 *
 * HOW IT WORKS:
 * - Triggered every 15 minutes (WorkManager minimum for PeriodicWorkRequest)
 * - Checks if we're on a 5-minute boundary
 * - Validates gap from last sync
 * - Triggers MyService.ACTION_CALL_API if conditions met
 * - Re-schedules AlarmManager if needed
 */
class PeriodicSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "PeriodicSyncWorker"
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "🔄 PeriodicSyncWorker executing at ${getCurrentTime()}")

        try {
            val user = SharedPref.getInstance(applicationContext)?.getUser()
            if (user == null) {
                Log.w(TAG, "❌ No user found - skipping work")
                return Result.success()
            }

            // Check if we're inside shift window
            if (!AlarmReceiver.isInsideShiftWindow(user)) {
                Log.i(TAG, "⏭️ Outside shift window - skipping")
                return Result.success()
            }

            Log.i(TAG, "✅ Inside shift window - checking sync conditions")

            // Check if service is running
            val isServiceRunning = com.shiftsmart.plus.utils.Utils.isServiceRunning(
                applicationContext,
                MyService::class.java
            )

            if (!isServiceRunning) {
                Log.w(TAG, "🚨 Service NOT running - starting service")
                startService()
                return Result.success()
            }

            // Check if we should sync
            val currentTime = Calendar.getInstance()
            val currentMinute = currentTime.get(Calendar.MINUTE)

            // Only sync on 5-minute boundaries
            if (currentMinute % 5 == 0) {
                val sharedPref = SharedPref.getInstance(applicationContext)
                val lastSyncTimestamp = sharedPref?.getLastSyncTimestamp() ?: 0L

                if (lastSyncTimestamp > 0L) {
                    val currentTimestamp = currentTime.apply {
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }.timeInMillis

                    val gapMinutes = ((currentTimestamp - lastSyncTimestamp) / (60 * 1000)).toInt()

                    Log.i(TAG, "⏱️ Last sync: ${formatTime(lastSyncTimestamp)}")
                    Log.i(TAG, "⏱️ Current: ${formatTime(currentTimestamp)}")
                    Log.i(TAG, "⏱️ Gap: $gapMinutes minutes")

                    if (gapMinutes >= 5 && gapMinutes % 5 == 0) {
                        Log.i(TAG, "✅ Gap valid ($gapMinutes min) - triggering sync")
                        triggerApiCall()
                    } else {
                        Log.i(TAG, "⏭️ Gap not valid ($gapMinutes min) - skipping")
                    }
                } else {
                    Log.i(TAG, "🆕 No last sync - triggering initial sync")
                    triggerApiCall()
                }
            } else {
                Log.i(TAG, "⏭️ Not on 5-min boundary (minute: $currentMinute) - skipping")
            }

            // Ensure AlarmManager is still scheduled
            AlarmScheduler.schedulePeriodicAlarm(applicationContext)

            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in PeriodicSyncWorker", e)
            return Result.retry()
        }
    }

    private fun startService() {
        val intent = Intent(applicationContext, MyService::class.java).apply {
            action = MyService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.startForegroundService(intent)
        } else {
            applicationContext.startService(intent)
        }
    }

    private fun triggerApiCall() {
        val intent = Intent(applicationContext, MyService::class.java).apply {
            action = MyService.ACTION_CALL_API
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.startForegroundService(intent)
        } else {
            applicationContext.startService(intent)
        }
    }

    private fun getCurrentTime(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
    }

    private fun formatTime(timestamp: Long): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
    }
}

