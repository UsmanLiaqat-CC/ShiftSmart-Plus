package com.shiftsmart.plus.periodicAction

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.shiftsmart.plus.models.TimeRange
import com.shiftsmart.plus.models.UserModel
import com.shiftsmart.plus.services.MyService
import com.shiftsmart.plus.ui.activities.WakeUpActivity
import com.shiftsmart.plus.utils.SharedPref
import com.shiftsmart.plus.utils.Utils
import com.shiftsmart.plus.utils.Utils.getCalendarForShift
import com.shiftsmart.plus.utils.Utils.getCurrentDayName
import com.shiftsmart.plus.utils.Utils.toLocalDate
import java.time.LocalDate
import java.util.Calendar
import java.util.Date

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        try {
            Log.i("TAG", "AlarmReceiver: onReceive at ${Utils.getCurrentDateTime()}")

            when (intent?.action) {
                "START_SERVICE" -> {
                    Log.i("AlarmReceiver", "Received START_SERVICE_ALARM")

                    val wakeIntent = Intent(context, WakeUpActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    context.startActivity(wakeIntent)

                    val serviceIntent = Intent(context, MyService::class.java).apply {
                        action = MyService.ACTION_START
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }

                    Log.i("AlarmReceiver", "Foreground service started successfully")
                }

                "STOP_SERVICE" -> {
                    Log.i("AlarmReceiver", "Received STOP_SERVICE_ALARM")
                    val stopIntent = Intent(context, MyService::class.java).apply {
                        action = MyService.ACTION_STOP
                    }
                    context.startService(stopIntent)
                }

                "CALL_API" -> {
                    Log.i("AlarmReceiver", "Received CALL_API")

                    val lastCallTime = SharedPref.getInstance(context)?.getLastApiCallTime()
                    val currentTime = System.currentTimeMillis()

                    Log.i("AlarmReceiver", "Last call: $lastCallTime, Current time: $currentTime")

                    // ✅ Ensure it's a new 5-minute bucket
                    val lastBucket = (lastCallTime ?: 0) / (5 * 60 * 1000)
                    val currentBucket = currentTime / (5 * 60 * 1000)

                    if (currentBucket != lastBucket) {
//                        val shifts = getShiftsFromSharedPreferences(context)

                        val user= SharedPref.getInstance(context)?.getUser()

                        user?.let { handleShiftPeriod(context, it) }

                        val apiIntent = Intent(context, MyService::class.java).apply {
                            action = MyService.ACTION_CALL_API
                        }
                        context.startService(apiIntent)

                        SharedPref.getInstance(context)?.saveLastApiCallTime(currentTime)
                        scheduleNextAlignedAlarm(context)

                        Log.i("AlarmReceiver", "API call executed at $currentTime")
                    } else {
                        Log.i("AlarmReceiver", "Same 5-minute bucket, skipping duplicate schedule.")
                    }
                }

            }

        } catch (e: Exception) {
            Log.e("TAG", "Error in AlarmReceiver onReceive", e)
        }
    }
    private fun handleShiftPeriod(
        context: Context,
        user: UserModel
    ) {
        try {
            val today = getCurrentDayName()
            val currentDate = LocalDate.now()

            // Step 1: Check for an active multipleTimeTable
            val activeMultiTable = user.multipleTimeTables?.find { mt ->
                val start = mt.startDate.toLocalDate()
                val end = mt.endDate.toLocalDate()
                currentDate in start..end
            }

            // Step 2: Select the appropriate shift range
            val effectiveShifts = activeMultiTable?.timetable?.range ?: user.timetable?.range.orEmpty()

            val todayShift = effectiveShifts.find { it.day.equals(today, ignoreCase = true) }

            if (todayShift != null && todayShift.start != null && todayShift.end != null) {
                Log.i("TAG", "Today's Shift -> day:${todayShift.day}, start:${todayShift.start}, end:${todayShift.end}")

                val startCalendar = getCalendarForShift(todayShift.day, todayShift.start, -1)
                val endCalendar = getCalendarForShift(todayShift.day, todayShift.end, 1)

                val currentTime = Calendar.getInstance()

                if (startCalendar != null && endCalendar != null) {
                    if (currentTime.after(startCalendar) && currentTime.before(endCalendar)) {
                        if (!isServiceRunning(context)) {
                            Log.i("TAG", "Service is not running. Scheduling alarms...")
                            // Only schedule for today's shift
                            AlarmScheduler.scheduleAlarms(context, listOf(todayShift),user.multipleTimeTables!!, reschedulePeriodic = false)
                        } else {
                            Log.i("TAG", "Service is already running.")
                        }
                    } else {
                        Log.i("TAG", "Current time is outside shift period, NOT scheduling API Worker.")
                    }
                }
            } else {
                Log.i("TAG", "No shift found for today.")
            }
        } catch (e: Exception) {
            Log.e("TAG", "Error in handleShiftPeriod", e)
        }
    }



    /*    private fun handleShiftPeriod(
            context: Context,
            shifts: List<TimeRange>
        ) {
            try {
                val today = getCurrentDayName()
                val todayShift = shifts.find { it.day.equals(today, ignoreCase = true) }

                if (todayShift != null && todayShift.start != null && todayShift.end != null) {
                    Log.i("TAG", "Today's Shift -> day:${todayShift.day}, start:${todayShift.start}, end:${todayShift.end}")

                    val startCalendar = getCalendarForShift(todayShift.day, todayShift.start, -1)
                    val endCalendar = getCalendarForShift(todayShift.day, todayShift.end, 1)

                    val currentTime = Calendar.getInstance()

                    if (startCalendar != null && endCalendar != null) {
                        if (currentTime.after(startCalendar) && currentTime.before(endCalendar)) {
                            if (!isServiceRunning(context)) {
                                Log.i("TAG", "Service is not running. Scheduling alarms...")

                                AlarmScheduler.scheduleAlarms(context, listOf(todayShift), reschedulePeriodic = false)
                            } else {
                                Log.i("TAG", "Service is already running.")
                            }
                        } else {
                            Log.i("TAG", "Current time is outside shift period, NOT scheduling API Worker.")
                        }
                    }
                } else {
                    Log.i("TAG", "No shift found for today.")
                }
            } catch (e: Exception) {
                Log.e("TAG", "Error in handleShiftPeriod", e)
            }
        }*/

    private fun isServiceRunning(context: Context): Boolean {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            activityManager.getRunningServices(Int.MAX_VALUE).any {
                it.service.className == MyService::class.java.name
            }
        } catch (e: Exception) {
            Log.e("TAG", "Error checking if service is running", e)
            false
        }
    }

    private fun getShiftsFromSharedPreferences(context: Context): List<TimeRange> {
        return try {
            SharedPref.getInstance(context)?.getUser()?.timetable?.range ?: emptyList()
        } catch (e: Exception) {
            Log.e("TAG", "Error retrieving shifts from SharedPreferences", e)
            emptyList()
        }
    }

    private fun scheduleNextAlignedAlarm(context: Context?) {
        val alarmManager = context?.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val now = Calendar.getInstance()
        val currentMillis = now.timeInMillis

        // Force next exact multiple of 5
        val nextAligned = (currentMillis / (5 * 60 * 1000) + 1) * (5 * 60 * 1000)

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "CALL_API"
        }

        // Cancel any existing one before scheduling again
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            1234,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)

        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            nextAligned,
            pendingIntent
        )

        Log.d("AlarmReceiver", "Next aligned alarm scheduled at: ${Date(nextAligned)}")
    }

}
