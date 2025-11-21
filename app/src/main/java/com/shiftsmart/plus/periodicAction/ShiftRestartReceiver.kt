package com.shiftsmart.plus.periodicAction

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.shiftsmart.plus.models.UserModel
import com.shiftsmart.plus.services.MyService
import com.shiftsmart.plus.utils.SharedPref
import com.shiftsmart.plus.utils.ShiftUtils
import com.shiftsmart.plus.utils.Utils
import com.shiftsmart.plus.utils.Utils.isServiceRunning
import com.shiftsmart.plus.utils.Utils.toLocalDate
import java.time.LocalDate
import kotlin.text.compareTo


class ShiftRestartReceiver : BroadcastReceiver() {
    private val TAG = "ShiftRestartReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "⏰ Restart alarm triggered - checking if service should start...")

//        val user = SharedPref.getInstance(context)?.getUser()
//        if (user == null) {
//            Log.e(TAG, "❌ No user found, cannot check shift status")
//            return
//        }

//        // Check if service is currently running
//        val isServiceRunning = Utils.isServiceRunning(context, MyService::class.java)
//
//        // Check if we're currently in shift time
//        val shouldRun = isInsideShiftWindow(user)
//
//        if (shouldRun) {
//            if (isServiceRunning) {
//                Log.i(TAG, "✅ Service already running - skipping restart")
//            } else {
//                Log.i(TAG, "✅ Inside shift window - starting service")
//                startMyService(context)
//            }
//        } else {
//            Log.i(TAG, "⏭️ Outside shift window - scheduling next alarm")
//        }
//
//        // Schedule next restart alarm regardless
//        ShiftRestartAlarmManager.scheduleNextShiftAlarm(context, user)

        val sharedPref = SharedPref.getInstance(context = context)
        val user = sharedPref?.getUser()

        if (user != null && AlarmReceiver.isInsideShiftWindow(user)) {
            Log.w(TAG, "🚨 Service destroyed during shift - scheduling emergency restart in 1 minute")
            handleUserFromKillService(context,user)
        }else{
            Log.e(TAG, "user null")
        }

    }

    fun handleUserFromKillService(context: Context, user: UserModel) {
        try {

            if (user.isActive) {
                Log.i(TAG, "User is active. Scheduling alarms.")

                val timetable = user.timetable?.range
                val multiTimeTables = user.multipleTimeTables

                AlarmScheduler.scheduleAlarms(
                    context = context,
                    defaultShifts = timetable!!,
                    multipleTimeTables = multiTimeTables!!
                )

            } else {
                Log.w(TAG, "User is not active. Skipping alarm scheduling.")

                if (isServiceRunning(context, MyService::class.java)) {
                    Log.i("MyFirebaseMessagingService", "Service is running. Stopping service.")

                    val notificationManager =context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    notificationManager.cancelAll()
                    Log.i("Service", "Service is running. Stopping it now.")
//                    context.stopService(Intent(context, MyService::class.java))
                    val stopIntent = Intent(context, MyService::class.java).apply {
                        action = MyService.ACTION_STOP
                    }
                    context.startService(stopIntent)
                } else {
                    Log.i(TAG, "Service is not running. No action needed.")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error handling user from FCM", e)
        }
    }

    private fun isInsideShiftWindow(user: UserModel): Boolean {
        try {
            val today = LocalDate.now()

            val activeMulti = user.multipleTimeTables?.find { mt ->
                val s = mt.startDate.toLocalDate()
                val e = mt.endDate.toLocalDate()
                today in s..e
            }

            val effectiveRange = activeMulti?.timetable?.range ?: user.timetable?.range

            if (effectiveRange.isNullOrEmpty()) {
                Log.w(TAG, "⚠️ No timetable found")
                return false
            }

            val todayDayName = Utils.getCurrentDayName()
            val todayShift = effectiveRange.find {
                it.day.equals(todayDayName, ignoreCase = true)
            }

            if (todayShift == null || todayShift.start == null || todayShift.end == null) {
                Log.i(TAG, "📅 No shift scheduled for today ($todayDayName)")
                return false
            }

            // ✅ Use ShiftUtils to apply ±1 hour buffer
            // If shift is 08:00-18:00, service runs 07:00-19:00
            // If shift is overnight 20:00-04:00, service runs 19:00-05:00 (next day)
            val now = java.util.Calendar.getInstance()
            val isInShift = ShiftUtils.isTimeWithinBufferRange(
                now,
                todayShift.start,
                todayShift.end
            )

            Log.i(TAG, "🕐 Shift: ${todayShift.start}-${todayShift.end} (with ±1h buffer), Inside: $isInShift")
            return isInShift

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error checking shift window", e)
            return false
        }
    }

    private fun startMyService(context: Context) {
        try {
            val serviceIntent = Intent(context, MyService::class.java).apply {
                action = MyService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.i(TAG, "✅ MyService started with ACTION_START")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error starting MyService", e)
        }
    }
}
