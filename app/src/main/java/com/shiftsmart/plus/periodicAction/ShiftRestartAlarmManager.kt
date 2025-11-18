package com.shiftsmart.plus.periodicAction

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.shiftsmart.plus.models.TimeRange
import com.shiftsmart.plus.models.UserModel
import com.shiftsmart.plus.utils.Utils
import com.shiftsmart.plus.utils.Utils.toLocalDate
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalTime
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.or
import kotlin.text.compareTo

/**
 * Manages alarms to automatically restart the service when shifts begin.
 *
 * DUAL REDUNDANCY APPROACH:
 * 1. PRIMARY: AlarmManager with exact alarms (most reliable when permissions granted)
 * 2. BACKUP: WorkManager (survives aggressive battery optimization better)
 *
 * This dual approach ensures service starts even on devices with:
 * - Xiaomi/MIUI aggressive battery optimization
 * - Samsung Sleeping Apps restrictions
 * - Huawei/EMUI Protected Apps limitations
 * - Any manufacturer that may delay/cancel AlarmManager
 */
object ShiftRestartAlarmManager {
    private const val TAG = "ShiftRestartAlarm"
    private const val RESTART_REQUEST_CODE = 9999
    private const val MIDNIGHT_CHECK_REQUEST_CODE = 9998
    private const val WORK_NAME_SHIFT_START = "shift_start_worker"

    /**
     * Schedules BOTH AlarmManager AND WorkManager to restart the service before next shift starts.
     * Also schedules a midnight check as backup.
     *
     * REDUNDANCY STRATEGY:
     * - AlarmManager: Best for exact timing, requires SCHEDULE_EXACT_ALARM permission on Android 12+
     * - WorkManager: Backup system, more resilient to battery optimization
     * - Both will check if service is running before starting (prevents double-start)
     */
    fun scheduleNextShiftAlarm(context: Context, user: UserModel) {
        Log.i(TAG, "📅 Scheduling next shift restart (DUAL: AlarmManager + WorkManager)...")

        val today = LocalDate.now()
        val activeMulti = user.multipleTimeTables?.find { mt ->
            val s = mt.startDate.toLocalDate()
            val e = mt.endDate.toLocalDate()
            today in s..e
        }
        val effectiveRange = activeMulti?.timetable?.range ?: user.timetable?.range

        if (effectiveRange == null) {
            Log.e(TAG, "❌ No timetable found, cannot schedule restart")
            return
        }

        val nextShiftTime = findNextShiftStartTime(effectiveRange)
        if (nextShiftTime != null) {
            // ✅ APPROACH 1: Schedule via AlarmManager (PRIMARY)
            scheduleAlarmAtTime(context, nextShiftTime, RESTART_REQUEST_CODE)
            Log.i(TAG, "✅ [PRIMARY] AlarmManager scheduled at: ${
                SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss",
                    Locale.getDefault()
                ).format(nextShiftTime.time)}")

            // ✅ APPROACH 2: Schedule via WorkManager (BACKUP)
            val delayMillis = nextShiftTime.timeInMillis - System.currentTimeMillis()
            if (delayMillis > 0) {
                scheduleWorkManagerBackup(context, delayMillis)
                Log.i(TAG, "✅ [BACKUP] WorkManager scheduled with ${delayMillis / 60000} min delay")
            }

        } else {
            Log.w(TAG, "⚠️ No upcoming shift found in timetable")
        }

        // Schedule midnight check as backup
        scheduleMidnightCheck(context)
    }

    /**
     * Schedules a daily alarm at midnight to check if service should restart.
     */
    private fun scheduleMidnightCheck(context: Context) {
        val midnight = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        scheduleAlarmAtTime(context, midnight, MIDNIGHT_CHECK_REQUEST_CODE)
        Log.i(TAG, "🕛 Scheduled midnight check at: ${
            SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss",
                Locale.getDefault()
            ).format(midnight.time)}")
    }

    /**
     * Finds the next shift start time from the timetable.
     * Checks today's remaining shifts and tomorrow's shifts.
     */
    private fun findNextShiftStartTime(shifts: List<TimeRange>): Calendar? {
        val now = Calendar.getInstance()
        val todayName = now.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.ENGLISH) ?: ""

        // Try today's shifts first
        val todayShift = shifts.find { it.day.equals(todayName, ignoreCase = true) }
        if (todayShift?.start != null) {
            val shiftStart = parseTimeToCalendar(todayShift.start, 0)
            if (shiftStart.after(now)) {
                // Shift hasn't started yet today
                return shiftStart.apply {
                    add(Calendar.MINUTE, -10) // Start 10 minutes before shift
                }
            }
        }

        // Try tomorrow's shifts
        val tomorrowCalendar = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
        val tomorrowName = tomorrowCalendar.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.ENGLISH) ?: ""

        val tomorrowShift = shifts.find { it.day.equals(tomorrowName, ignoreCase = true) }
        if (tomorrowShift?.start != null) {
            return parseTimeToCalendar(tomorrowShift.start, 1).apply {
                add(Calendar.MINUTE, -10) // Start 10 minutes before shift
            }
        }

        // Try rest of the week
        for (dayOffset in 2..7) {
            val futureCalendar = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, dayOffset) }
            val futureDayName = futureCalendar.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.ENGLISH) ?: ""

            val futureShift = shifts.find { it.day.equals(futureDayName, ignoreCase = true) }
            if (futureShift?.start != null) {
                return parseTimeToCalendar(futureShift.start, dayOffset).apply {
                    add(Calendar.MINUTE, -10)
                }
            }
        }

        return null
    }

    /**
     * Parses time string (HH:mm) to Calendar with day offset.
     */
    private fun parseTimeToCalendar(timeStr: String, dayOffset: Int): Calendar {
        val time = Utils.parseFlexibleTime(timeStr) ?: LocalTime.of(9, 0)
        return Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, dayOffset)
            set(Calendar.HOUR_OF_DAY, time.hour)
            set(Calendar.MINUTE, time.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }

    /**
     * Schedules an exact alarm at the specified time.
     */
    private fun scheduleAlarmAtTime(context: Context, time: Calendar, requestCode: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ShiftRestartReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                    if (alarmManager.canScheduleExactAlarms()) {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            time.timeInMillis,
                            pendingIntent
                        )
                        Log.i(TAG, "✅ Exact alarm scheduled")
                    } else {
                        Log.e(TAG, "❌ Cannot schedule exact alarms - using fallback")
                        // Fallback to inexact alarm
                        alarmManager.setAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            time.timeInMillis,
                            pendingIntent
                        )
                    }
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        time.timeInMillis,
                        pendingIntent
                    )
                    Log.i(TAG, "✅ Exact alarm scheduled")
                }
                else -> {
                    alarmManager.setExact(
                        AlarmManager.RTC_WAKEUP,
                        time.timeInMillis,
                        pendingIntent
                    )
                    Log.i(TAG, "✅ Exact alarm scheduled")
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ SecurityException - trying inexact alarm", e)
            try {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    time.timeInMillis,
                    pendingIntent
                )
            } catch (e2: Exception) {
                Log.e(TAG, "❌ All alarm methods failed", e2)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error scheduling alarm", e)
        }
    }

    /**
     * Schedules a WorkManager job as BACKUP to start service at shift time.
     * This runs alongside AlarmManager to ensure service starts even if AlarmManager
     * is delayed/cancelled by aggressive battery optimization.
     *
     * WHY THIS IS NEEDED:
     * - AlarmManager may be delayed in Doze mode on some manufacturers
     * - Xiaomi/MIUI may kill exact alarms despite permissions
     * - WorkManager survives better through device restrictions
     * - Both approaches together provide 99%+ reliability
     *
     * @param context Application context
     * @param delayMillis Delay in milliseconds until shift start
     */
    private fun scheduleWorkManagerBackup(context: Context, delayMillis: Long) {
        try {
            val workRequest = OneTimeWorkRequestBuilder<ShiftStartOneTimeWorker>()
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .addTag(WORK_NAME_SHIFT_START)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    WORK_NAME_SHIFT_START,
                    ExistingWorkPolicy.REPLACE, // Replace any existing work
                    workRequest
                )

            Log.i(TAG, "✅ WorkManager backup scheduled successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error scheduling WorkManager backup", e)
        }
    }

}