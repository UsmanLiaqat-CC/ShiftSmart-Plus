package com.shiftsmart.plus.periodicAction

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.shiftsmart.plus.models.MultipleTimeTable
import com.shiftsmart.plus.models.TimeRange
import com.shiftsmart.plus.services.MyService
import com.shiftsmart.plus.utils.Utils
import com.shiftsmart.plus.utils.Utils.getCalendarForShift
import com.shiftsmart.plus.utils.Utils.getCurrentDayName
import com.shiftsmart.plus.utils.Utils.toLocalDate
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Date

object AlarmScheduler {
    private const val TAG = "AlarmScheduler"
/*    fun scheduleAlarms(
        context: Context,
        shifts: List<TimeRange>,
        reschedulePeriodic: Boolean = true
    ) {
        Log.i(TAG, "scheduleAlarms: ${Utils.getCurrentDateTime()}")

        val today = getCurrentDayName() // Get today's name, e.g., "Tuesday"
        Log.i(TAG, "Today's Day: $today")

        val todayShift = shifts.find { it.day.equals(today, ignoreCase = true) }

        if (todayShift != null && todayShift.start != null && todayShift.end != null) {
            Log.i(
                TAG,
                "Today's Shift -> day:${todayShift.day}, start:${todayShift.start}, end:${todayShift.end}"
            )

            val startCalendar = getCalendarForShift(todayShift.day, todayShift.start, -1)
            val endCalendar = getCalendarForShift(todayShift.day, todayShift.end, 1)

            Log.i(
                TAG,
                "startCalendar: ${startCalendar?.time} --> endCalendar: ${endCalendar?.time}"
            )

            if (startCalendar != null && endCalendar != null) {

                scheduleService(context, startCalendar, true)
                scheduleService(context, endCalendar, false)

                if (reschedulePeriodic) {
                    schedulePeriodicAlarm(context)
                }
            }
        } else {
            Log.i(TAG, "No shift found for today.")
        }
    }*/

    fun scheduleAlarms(
        context: Context,
        defaultShifts: List<TimeRange>,
        multipleTimeTables: List<MultipleTimeTable>,
        reschedulePeriodic: Boolean = true
    ) {
        val TAG = "AlarmScheduler"
        Log.i(TAG, "scheduleAlarms: ${Utils.getCurrentDateTime()}")

        val today = getCurrentDayName()
        val currentDate = LocalDate.now()

        // Step 1: Find matching multiple timetable for today
        val activeMultiTable = multipleTimeTables.find { mt ->
            val startDate = mt.startDate.toLocalDate()
            val endDate = mt.endDate.toLocalDate()
            currentDate in startDate..endDate
        }

        val effectiveRange = activeMultiTable?.timetable?.range ?: defaultShifts

        if (activeMultiTable != null) {
            Log.i(TAG, "Using multipleTimeTable: ${activeMultiTable.timetable.timeTableName}")
        } else {
            Log.i(TAG, "Using default timetable")
        }

        // Step 2: Use today's shift from the effective timetable
        val todayShift = effectiveRange.find { it.day.equals(today, ignoreCase = true) }

        if (todayShift != null && todayShift.start != null && todayShift.end != null) {
            Log.i(TAG, "Today's Shift -> day:${todayShift.day}, start:${todayShift.start}, end:${todayShift.end}")

            val startCalendar = getCalendarForShift(todayShift.day, todayShift.start, -1)
            val endCalendar = getCalendarForShift(todayShift.day, todayShift.end, 1)

            Log.i(TAG, "startCalendar: ${startCalendar?.time} --> endCalendar: ${endCalendar?.time}")

            if (startCalendar != null && endCalendar != null) {
                scheduleService(context, startCalendar, true)
                scheduleService(context, endCalendar, false)

                if (reschedulePeriodic) {
                    schedulePeriodicAlarm(context)
                }
            }
        } else {
            Log.i(TAG, "No shift found for today.")
        }
    }

    fun schedulePeriodicAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // Android 12+ (API 31): Check if exact alarm permission is granted
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!alarmManager.canScheduleExactAlarms()) {
                Log.w(
                    "AlarmScheduler",
                    "Exact alarm permission not granted. Redirecting to settings."
                )
                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                return
            }
        }

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "CALL_API"
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            1234,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        alarmManager.cancel(pendingIntent)

        val triggerTime = getNextFiveMinuteAlignedTime()
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerTime,
            pendingIntent
        )

        Log.i("AlarmScheduler", "Scheduled CALL_API alarm at ${Date(triggerTime)}")
    }


    private fun scheduleService(context: Context, calendar: Calendar, isStart: Boolean) {
        Log.i(TAG, "Scheduling Service at ${calendar.time}, isStart: $isStart")

        try {
            val intent = Intent(context, MyService::class.java).apply {
                action = if (isStart) "START_SERVICE" else "STOP_SERVICE"
            }
            val requestCode = if (isStart) 1001 else 1002
            val pendingIntent = PendingIntent.getService(
                context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE
            )

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                calendar.timeInMillis,
                pendingIntent
            )

            Log.i(TAG, "Scheduled ${if (isStart) "start" else "stop"} service at: ${calendar.time}")
            // ✅ Ensure device wakes up for the alarm (Important!)
            acquireWakeLock(context)
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling service", e)
        }
    }

    private fun acquireWakeLock(context: Context) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "MyApp::AlarmFullWakeLock"
        )

        if (!wakeLock.isHeld) {
            wakeLock.acquire(10 * 1000L) // Wake screen for 10 seconds
            Log.i("AlarmScheduler", "Wake lock acquired and screen turned on.")
        }
    }

    private fun getNextFiveMinuteAlignedTime(): Long {
        val now = Calendar.getInstance()
        val minutes = now.get(Calendar.MINUTE)
        val extra = 5 - (minutes % 5)
        now.add(Calendar.MINUTE, extra)
        now.set(Calendar.SECOND, 0)
        now.set(Calendar.MILLISECOND, 0)
        return now.timeInMillis
    }

}
