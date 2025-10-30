package com.shiftsmart.plus.periodicAction

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.shiftsmart.plus.models.UserModel
import com.shiftsmart.plus.services.MyService
import com.shiftsmart.plus.utils.SharedPref
import com.shiftsmart.plus.utils.Utils
import com.shiftsmart.plus.utils.Utils.toLocalDate
import java.time.LocalDate
import kotlin.text.compareTo


class ShiftRestartReceiver : BroadcastReceiver() {
    private val TAG = "ShiftRestartReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "⏰ Restart alarm triggered - checking if service should start...")

        val user = SharedPref.getInstance(context)?.getUser()
        if (user == null) {
            Log.e(TAG, "❌ No user found, cannot check shift status")
            return
        }

        // Check if service is currently running
        val isServiceRunning = Utils.isServiceRunning(context, MyService::class.java)

        // Check if we're currently in shift time
        val shouldRun = isInsideShiftWindow(user)

        if (shouldRun) {
            if (isServiceRunning) {
                Log.i(TAG, "✅ Service already running - skipping restart")
            } else {
                Log.i(TAG, "✅ Inside shift window - starting service")
                startMyService(context)
            }
        } else {
            Log.i(TAG, "⏭️ Outside shift window - scheduling next alarm")
        }

        // Schedule next restart alarm regardless
        ShiftRestartAlarmManager.scheduleNextShiftAlarm(context, user)
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

            val now = java.time.LocalTime.now()
            val shiftStart = Utils.parseFlexibleTime(todayShift.start)
            val shiftEnd = Utils.parseFlexibleTime(todayShift.end)

            if (shiftStart == null || shiftEnd == null) {
                Log.e(TAG, "❌ Failed to parse shift times")
                return false
            }

            val isInShift = if (shiftEnd.isBefore(shiftStart)) {
                now.isAfter(shiftStart) || now.isBefore(shiftEnd)
            } else {
                now.isAfter(shiftStart) && now.isBefore(shiftEnd)
            }

            Log.i(TAG, "🕐 Current: $now, Shift: $shiftStart-$shiftEnd, Inside: $isInShift")
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
