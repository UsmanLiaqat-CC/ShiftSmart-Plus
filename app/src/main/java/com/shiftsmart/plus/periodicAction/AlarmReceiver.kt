package com.shiftsmart.plus.periodicAction

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.shiftsmart.plus.models.TimeRange
import com.shiftsmart.plus.services.MyService
import com.shiftsmart.plus.ui.activities.WakeUpActivity
import com.shiftsmart.plus.utils.SharedPref
import com.shiftsmart.plus.utils.Utils
import com.shiftsmart.plus.utils.Utils.getCalendarForShift
import com.shiftsmart.plus.utils.Utils.getCurrentDayName
import java.util.Calendar

// Updated AlarmReceiver

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        try {
            Log.i("TAG", "AlarmReceiver: onReceive at ${Utils.getCurrentDateTime()}")

            when (intent?.action) {
                "START_SERVICE" -> {
                    Log.i("AlarmReceiver", "Received START_SERVICE_ALARM")

                    // Optional: Wake screen via activity (only if needed)
                    val wakeIntent = Intent(context, WakeUpActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    context.startActivity(wakeIntent)

                    val serviceIntent = Intent(context, MyService::class.java).apply {
                        action = "START_SERVICE"
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
                        action = "STOP_SERVICE"
                    }
                    context.startService(stopIntent)
                }
                "CHECK_SERVICE" -> {
                    Log.i("AlarmReceiver", "Received CHECK_SERVICE")
                    val shifts = getShiftsFromSharedPreferences(context) // Implement this function to get shifts
                    // Proceed with the logic to handle the service start/stop only if within the shift period
                    handleShiftPeriod(context, shifts)
                }
                // ... handle CHECK_SERVICE if needed
            }

        } catch (e: Exception) {
            Log.e("TAG", "Error in AlarmReceiver onReceive", e)
        }
    }

    private fun handleShiftPeriod(context: Context, shifts: List<TimeRange>) {
        try {
            val today = getCurrentDayName() // Get today's name (e.g., "Tuesday")

            val todayShift = shifts.find { it.day.equals(today, ignoreCase = true) }

            if (todayShift != null && todayShift.start != null && todayShift.end != null) {
                Log.i("TAG", "Today's Shift -> day:${todayShift.day}, start:${todayShift.start}, end:${todayShift.end}")

                val startCalendar = getCalendarForShift(todayShift.day, todayShift.start, -1)
                val endCalendar = getCalendarForShift(todayShift.day, todayShift.end, 1)

                val currentTime = Calendar.getInstance()

                if (startCalendar != null && endCalendar != null) {
                    // Check if the current time is between shift start & end
                    if (currentTime.after(startCalendar) && currentTime.before(endCalendar)) {
                        // Check if the service is running
                        if (!isServiceRunning(context)) {
                            // If service is not running, schedule alarms
                            Log.i("TAG", "Service is not running. Scheduling alarms...")
                            AlarmScheduler.scheduleAlarms(context, listOf(todayShift))
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

    // Helper function to check if MyService is running
    private fun isServiceRunning(context: Context): Boolean {
        try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val services = activityManager.getRunningServices(Int.MAX_VALUE)

            // Check if MyService is running
            for (service in services) {
                if (MyService::class.java.name == service.service.className) {
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e("TAG", "Error checking if service is running", e)
        }
        return false
    }

    // Function to get shifts from shared preferences or another storage method
    private fun getShiftsFromSharedPreferences(context: Context): List<TimeRange> {
        try {
            // Retrieve shifts from SharedPreferences, database, or any other method
            // For example:
            return SharedPref.getInstance(context)?.getUser()?.timetable?.range ?: emptyList()
        } catch (e: Exception) {
            Log.e("TAG", "Error retrieving shifts from SharedPreferences", e)
        }
        return emptyList()
    }
}
