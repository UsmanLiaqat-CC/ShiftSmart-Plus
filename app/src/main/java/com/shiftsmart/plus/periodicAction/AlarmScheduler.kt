package com.shiftsmart.plus.periodicAction

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.shiftsmart.plus.database.DbConstants.RECORD_INTERVAL
import com.shiftsmart.plus.models.TimeRange
import com.shiftsmart.plus.services.MyService
import com.shiftsmart.plus.utils.Utils
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

            Log.i(TAG, "startCalendar:$startCalendar --> endCalendar:$endCalendar")

            if (startCalendar != null && endCalendar != null) {
                scheduleService(context, startCalendar, true)
                scheduleService(context, endCalendar, false)
            }
        } else {
            Log.i(TAG, "No shift found for today.")
        }

        // ✅ Schedule WorkManager for API Calls
        scheduleApiWorker(context)
    }
    private fun scheduleService(context: Context, calendar: Calendar, isStart: Boolean) {
        Log.i(TAG, "Scheduling Service at ${calendar.time}, isStart: $isStart")

        try {
            val intent = Intent(context, MyService::class.java).apply {
                action = if (isStart) "START_SERVICE" else "STOP_SERVICE"
            }

            val pendingIntent = PendingIntent.getService(
                context,
                calendar[Calendar.DAY_OF_YEAR] + (if (isStart) 0 else 1),
                intent,
                PendingIntent.FLAG_IMMUTABLE
            )

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)

            Log.i(TAG, "Scheduled ${if (isStart) "start" else "stop"} service at: ${calendar.time}")
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling service", e)
        }
    }

    /*fun scheduleApiWorker(context: Context) {
        Log.i(TAG, "Scheduling API Worker")

        val oneTimeRequest = OneTimeWorkRequestBuilder<ApiWorker>()
            .setInitialDelay(RECORD_INTERVAL.toLong(), TimeUnit.MINUTES) // Customize the delay as ne1eded
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "API_WORK",
            ExistingWorkPolicy.REPLACE, // Replaces any existing work with the same name
            oneTimeRequest
        )


        Log.i(TAG, "API Worker Scheduled")
    }*/
    fun scheduleApiWorker(context: Context) {
        Log.i(TAG, "Scheduling API Worker")

        val workManager = WorkManager.getInstance(context)
        workManager.getWorkInfosForUniqueWorkLiveData("API_WORK").observeForever { workInfos ->
            val isAlreadyScheduled = workInfos.any {
                it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING
            }

            if (!isAlreadyScheduled) {
                val oneTimeRequest = OneTimeWorkRequestBuilder<ApiWorker>()
                    .setInitialDelay(RECORD_INTERVAL.toLong(), TimeUnit.MINUTES)
                    .build()

                workManager.enqueueUniqueWork(
                    "API_WORK",
                    ExistingWorkPolicy.KEEP, // Keep the existing work instead of replacing
                    oneTimeRequest
                )
                Log.i(TAG, "API Worker Scheduled")
            } else {
                Log.i(TAG, "API Worker is already scheduled, skipping duplicate scheduling")
            }
        }
    }


    private fun getCalendarForShift(day: String?, time: String?, hourOffset: Int): Calendar? {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())

        return try {
            val parsedTime = sdf.parse(time) ?: return null

            val now = Calendar.getInstance()
            val shiftCalendar = Calendar.getInstance()

            // Set the correct hour, minute, and second
            shiftCalendar.time = parsedTime
            shiftCalendar.set(Calendar.YEAR, now.get(Calendar.YEAR))
            shiftCalendar.set(Calendar.MONTH, now.get(Calendar.MONTH))
            shiftCalendar.set(Calendar.DAY_OF_MONTH, now.get(Calendar.DAY_OF_MONTH)) // Set to today
            shiftCalendar.set(Calendar.SECOND, 0)
            shiftCalendar.set(Calendar.MILLISECOND, 0)

            // Ensure we are scheduling for the correct weekday
            val targetDay = getDayOfWeek(day)
            while (shiftCalendar.get(Calendar.DAY_OF_WEEK) != targetDay) {
                shiftCalendar.add(Calendar.DAY_OF_YEAR, 1) // Move to the correct day
            }

            // Apply hour offset (after setting the correct day)
            shiftCalendar.add(Calendar.HOUR_OF_DAY, hourOffset)

            Log.i(TAG, "Shift Time: ${shiftCalendar.time}")
            shiftCalendar

        } catch (e: ParseException) {
            Log.e(TAG, "Invalid time format", e)
            null
        }
    }
    private fun getCurrentDayName(): String {
        val calendar = Calendar.getInstance()
        return SimpleDateFormat("EEEE", Locale.getDefault()).format(calendar.time)
    }
    private fun getDayOfWeek(day: String?): Int {
        Log.i(TAG, "getDayOfWeek: ")
        return when (day!!.lowercase(Locale.getDefault())) {
            "monday" -> Calendar.MONDAY
            "tuesday" -> Calendar.TUESDAY
            "wednesday" -> Calendar.WEDNESDAY
            "thursday" -> Calendar.THURSDAY
            "friday" -> Calendar.FRIDAY
            "saturday" -> Calendar.SATURDAY
            "sunday" -> Calendar.SUNDAY
            else -> -1
        }
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
