package com.shiftsmart.plus.periodicAction

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.shiftsmart.plus.models.UserModel
import com.shiftsmart.plus.services.MyService
import com.shiftsmart.plus.utils.SharedPref
import com.shiftsmart.plus.utils.ShiftUtils
import com.shiftsmart.plus.utils.Utils
import com.shiftsmart.plus.utils.Utils.toLocalDate
import java.time.LocalDate

/**
 * WorkManager Worker that acts as a BACKUP mechanism to start the service
 * when shift begins. This runs as a fallback if AlarmManager fails due to
 * aggressive battery optimization on some devices.
 *
 * This provides redundancy to ensure service starts even on:
 * - Xiaomi/MIUI devices with strict battery optimization
 * - Samsung devices with aggressive Doze mode
 * - Other manufacturers that may delay/cancel AlarmManager
 */
class ShiftStartWorker(
    context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {

    private val TAG = "ShiftStartWorker"

    override fun doWork(): Result {
        Log.i(TAG, "⏰ WorkManager triggered for shift check at ${Utils.getCurrentDateTime()}")

        val user = SharedPref.getInstance(applicationContext)?.getUser()
        if (user == null) {
            Log.e(TAG, "❌ No user found, cannot check shift status")
            return Result.failure()
        }

        // Check if we're currently in shift time
        val shouldStartService = isInsideShiftWindow(user)

        if (shouldStartService) {
            // Check if service is already running
            val isServiceRunning = Utils.isServiceRunning(applicationContext, MyService::class.java)

            if (isServiceRunning) {
                Log.i(TAG, "✅ Service already running - WorkManager backup not needed")
            } else {
                Log.i(TAG, "🚨 Service NOT running but should be - Starting via WorkManager BACKUP")
                startMyService()
            }
        } else {
            Log.i(TAG, "⏭️ Outside shift window - no action needed")
        }

        // Always reschedule next alarm/worker regardless
        ShiftRestartAlarmManager.scheduleNextShiftAlarm(applicationContext, user)

        return Result.success()
    }

    private fun isInsideShiftWindow(user: UserModel): Boolean {
        return try {
            val today = LocalDate.now()

            val activeMulti = user.multipleTimeTables?.find { mt ->
                val s = mt.startDate.toLocalDate()
                val e = mt.endDate.toLocalDate()
                today in s..e
            }

            val effectiveRange = activeMulti?.timetable?.range ?: user.timetable?.range

            when {
                effectiveRange.isNullOrEmpty() -> {
                    Log.w(TAG, "⚠️ No timetable found")
                    false
                }
                else -> {
                    val todayDayName = Utils.getCurrentDayName()
                    val todayShift = effectiveRange.find {
                        it.day.equals(todayDayName, ignoreCase = true)
                    }

                    when {
                        todayShift?.start.isNullOrBlank() || todayShift?.end.isNullOrBlank() -> {
                            Log.i(TAG, "📅 No shift scheduled for today ($todayDayName)")
                            false
                        }
                        else -> {
                            // ✅ Use ShiftUtils to apply ±1 hour buffer
                            // If shift is 08:00-18:00, service runs 07:00-19:00
                            // If shift is overnight 20:00-04:00, service runs 19:00-05:00 (next day)
                            val now = java.util.Calendar.getInstance()
                            val isInShift = ShiftUtils.isTimeWithinBufferRange(
                                now,
                                todayShift.start!!,
                                todayShift.end!!
                            )

                            Log.i(TAG, "🕐 Shift: ${todayShift.start}-${todayShift.end} (with ±1h buffer)")
                            Log.i(TAG, "   Inside: $isInShift")
                            isInShift
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error checking shift window", e)
            false
        }
    }

    private fun startMyService() {
        try {
            val serviceIntent = Intent(applicationContext, MyService::class.java).apply {
                action = MyService.ACTION_START
            }
            applicationContext.startForegroundService(serviceIntent)
            Log.i(TAG, "✅ MyService started via WorkManager backup")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error starting MyService", e)
        }
    }
}
