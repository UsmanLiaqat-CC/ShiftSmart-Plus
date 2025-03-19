package com.shiftsmart.plus.periodicAction

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
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
        Log.i(TAG, "scheduleAlarms: ${Utils.getCurrentDateTime()}")

        val today = getCurrentDayName() // Get today's name, e.g., "Tuesday"
        Log.i(TAG, "Today's Day: $today")

        val todayShift = shifts.find { it.day.equals(today, ignoreCase = true) }

        if (todayShift != null && todayShift.start != null && todayShift.end != null) {
            Log.i(TAG, "Today's Shift -> day:${todayShift.day}, start:${todayShift.start}, end:${todayShift.end}")

            val startCalendar = getCalendarForShift(todayShift.day, todayShift.start, -1)
            val endCalendar = getCalendarForShift(todayShift.day, todayShift.end, 1)

            Log.i(TAG, "startCalendar: ${startCalendar?.time} --> endCalendar: ${endCalendar?.time}")

            val currentTime = Calendar.getInstance()

            if (startCalendar != null && endCalendar != null) {
                scheduleService(context, startCalendar, true)
                scheduleService(context, endCalendar, false)

//                // ✅ Schedule API Worker ONLY IF current time is between shift start & end
//                if (currentTime.after(startCalendar) && currentTime.before(endCalendar)) {
//                    Log.i(TAG, "Current time is within shift period, scheduling API Worker.")
//                    scheduleApiWorker(context)
//                } else {
//                    Log.i(TAG, "Current time is outside shift period, NOT scheduling API Worker.")
//                }
            }
        } else {
            Log.i(TAG, "No shift found for today.")
        }
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
            PowerManager.PARTIAL_WAKE_LOCK,
            "MyApp::AlarmWakeLock"
        )
        wakeLock.acquire(10 * 60 * 1000L) // Hold for 10 minutes (enough for alarm trigger)
    }

    fun scheduleApiWorker(context: Context) {
        Log.i(TAG, "Scheduling API Worker at ${Utils.getCurrentDateTime()}")


        val workManager = WorkManager.getInstance(context)

        val oneTimeRequest = OneTimeWorkRequestBuilder<ApiWorker>()
            .setInitialDelay(RECORD_INTERVAL.toLong(), TimeUnit.MINUTES)
            .addTag("API_WORK") // Add a tag for easy retrieval
            .build()

        workManager.enqueueUniqueWork(
            "API_WORK",
            ExistingWorkPolicy.REPLACE,
            oneTimeRequest
        )
        Log.i(TAG, "API Worker Scheduled")
    }

    fun cancelAlarms(context: Context) {
        Log.i(TAG, "cancelAlarms: ")
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // Cancel Start Service Alarm
        val startIntent = Intent(context, MyService::class.java).apply { action = "START_SERVICE" }
        val startPendingIntent = PendingIntent.getService(context, 0, startIntent,  PendingIntent.FLAG_IMMUTABLE)
        alarmManager.cancel(startPendingIntent)

        // Cancel Stop Service Alarm
        val stopIntent = Intent(context, MyService::class.java).apply { action = "STOP_SERVICE" }
        val stopPendingIntent = PendingIntent.getService(context, 1, stopIntent,  PendingIntent.FLAG_IMMUTABLE)
        alarmManager.cancel(stopPendingIntent)

        // Cancel API Worker Alarm
        val apiIntent = Intent(context, AlarmReceiver::class.java)
        val apiPendingIntent = PendingIntent.getBroadcast(context, 0, apiIntent,  PendingIntent.FLAG_IMMUTABLE)
        alarmManager.cancel(apiPendingIntent)

        Log.d(TAG, "All alarms canceled.")
    }

}
