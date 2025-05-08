package com.shiftsmart.plus.periodicAction

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.shiftsmart.plus.database.DbConstants.RECORD_INTERVAL
import com.shiftsmart.plus.models.TimeRange
import com.shiftsmart.plus.services.MyService
import com.shiftsmart.plus.utils.Utils
import com.shiftsmart.plus.utils.Utils.getCalendarForShift
import com.shiftsmart.plus.utils.Utils.getCurrentDayName
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

object AlarmScheduler {
    private const val TAG = "AlarmScheduler"
    fun scheduleAlarms(context: Context, shifts: List<TimeRange>) {
        Log.i(TAG, "scheduleAlarms: ${Utils.getCurrentDateTime()}\nshift:${shifts}")

        val today = getCurrentDayName() // Get today's name, e.g., "Tuesday"
        Log.i(TAG, "Today's Day: $today")

        val todayShift = shifts.find { it.day.equals(today, ignoreCase = true) }

        if (todayShift != null && todayShift.start != null && todayShift.end != null) {
            Log.i(TAG, "Today's Shift -> day:${todayShift.day}, start:${todayShift.start}, end:${todayShift.end}")

            val startCalendar = getCalendarForShift(todayShift.day, todayShift.start, -1)
            val endCalendar = getCalendarForShift(todayShift.day, todayShift.end, 1)

            Log.i(TAG, "startCalendar: ${startCalendar?.time} --> endCalendar: ${endCalendar?.time}")

            if (startCalendar != null && endCalendar != null) {

                scheduleService(context, startCalendar, true)
                scheduleService(context, endCalendar, false)

                schedulePeriodicAlarm(context)
                scheduleRestartAlarm(context)
            }
        } else {
            Log.i(TAG, "No shift found for today.")
        }
    }

    // This will schedule an alarm that runs every 30 seconds
    fun schedulePeriodicAlarm(context: Context) {
        Log.i(TAG, "Scheduling periodic alarm every 30 seconds")

        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, AlarmReceiver::class.java).apply {
                action = "CHECK_SERVICE"
            }
            val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)

            // Setting the alarm to trigger every 30 seconds
            val triggerTime = SystemClock.elapsedRealtime() + 30000 // 30 seconds in milliseconds
            alarmManager.setRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerTime,
                30000, // Interval of 30 seconds
                pendingIntent
            )

            Log.i(TAG, "Alarm scheduled to run every 30 seconds.")
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling periodic alarm", e)
        }
    }

    fun scheduleRestartAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "CALL_API"
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            1234, // Keep the same ID so it gets replaced
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
//        val triggerTime = System.currentTimeMillis() + 5 * 60 * 1000

        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis(),
            pendingIntent
        )

        Log.i("AlarmScheduler", "Scheduled RESTART_SERVICE alarm in 5 minutes")
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
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)

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

}
