package com.shiftsmart.plus.periodicAction

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.shiftsmart.plus.models.TimeRange
import com.shiftsmart.plus.models.UserModel
import com.shiftsmart.plus.utils.Utils
import com.shiftsmart.plus.utils.Utils.toLocalDate
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalTime
import java.util.Calendar
import java.util.Locale

/**
 * Manages alarms to automatically restart the service when shifts begin.
 * This acts as a backup mechanism to ensure service runs during shift hours.
 */
object ShiftRestartAlarmManager {
    private const val TAG = "ShiftRestartAlarm"
    private const val RESTART_REQUEST_CODE = 9999
    private const val MIDNIGHT_CHECK_REQUEST_CODE = 9998

    /**
     * Schedules an alarm to restart the service before the next shift starts.
     * Also schedules a midnight check as backup.
     */
    fun scheduleNextShiftAlarm(context: Context, user: UserModel) {
        Log.i(TAG, "📅 Scheduling next shift restart alarm...")

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
            scheduleAlarmAtTime(context, nextShiftTime, RESTART_REQUEST_CODE)
            Log.i(TAG, "✅ Scheduled restart alarm at: ${
                SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss",
                    Locale.getDefault()
                ).format(nextShiftTime.time)}")
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        time.timeInMillis,
                        pendingIntent
                    )
                } else {
                    Log.e(TAG, "❌ Cannot schedule exact alarms - permission denied")
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    time.timeInMillis,
                    pendingIntent
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error scheduling alarm", e)
        }
    }

    /**
     * Cancels all scheduled restart alarms.
     */
    fun cancelAllAlarms(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // Cancel shift restart alarm
        val restartIntent = Intent(context, ShiftRestartReceiver::class.java)
        val restartPendingIntent = PendingIntent.getBroadcast(
            context,
            RESTART_REQUEST_CODE,
            restartIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(restartPendingIntent)

        // Cancel midnight check alarm
        val midnightIntent = Intent(context, ShiftRestartReceiver::class.java)
        val midnightPendingIntent = PendingIntent.getBroadcast(
            context,
            MIDNIGHT_CHECK_REQUEST_CODE,
            midnightIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(midnightPendingIntent)

        Log.i(TAG, "❌ Cancelled all restart alarms")
    }
}