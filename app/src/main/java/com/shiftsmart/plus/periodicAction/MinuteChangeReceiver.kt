package com.shiftsmart.plus.periodicAction

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.shiftsmart.plus.models.UserModel
import com.shiftsmart.plus.services.MyService
import com.shiftsmart.plus.utils.AttendanceSyncManager
import com.shiftsmart.plus.utils.SharedPref
import com.shiftsmart.plus.utils.ShiftUtils
import com.shiftsmart.plus.utils.Utils
import com.shiftsmart.plus.utils.Utils.getCurrentDayName
import com.shiftsmart.plus.utils.Utils.toLocalDate
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject
import kotlin.jvm.java


class MinuteChangeReceiver : BroadcastReceiver() {



    private val TAG = "MinuteChangeReceiver"

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent?.action != Intent.ACTION_TIME_TICK) return

        Log.d(TAG, "⏰ Minute changed - Checking shift status")

        val user = SharedPref.getInstance(context)?.getUser()
        if (user == null) {
            Log.w(TAG, "❌ No user found - skipping check")
            return
        }



        // Check if currently in shift using AttendanceSyncManager
        val isInShift =shouldRunCheck(user)

        if (isInShift) {
            Log.i(TAG, "✅ In active shift - Checking service status")
            
            // Check if service is running
            val isServiceRunning = isMyServiceRunning(context, MyService::class.java)
            
            if (!isServiceRunning) {
                Log.w(TAG, "⚠️ Service not running during shift - Starting service")
                startMyService(context)
            } else {
                Log.d(TAG, "✅ Service already running")
            }
        }
        else {
            Log.d(TAG, "❌ Outside shift - Ensuring service is stopped")
            
            val isServiceRunning = isMyServiceRunning(context, MyService::class.java)
            if (isServiceRunning) {
                Log.i(TAG, "🛑 Stopping service as shift ended")
                stopMyService(context)
            }
        }
    }

    fun shouldRunCheck(user: UserModel): Boolean {
        val today = LocalDate.now()
        val currentTimeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date())
        val todayDayName = getCurrentDayName()

        // Get yesterday's day name for overnight shift checking
        val yesterdayCalendar = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val yesterdayDayName = yesterdayCalendar.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.ENGLISH) ?: ""

        // Pick active multiple timetable (if any)
        val activeMulti = user.multipleTimeTables?.find { mt ->
            val s = mt.startDate.toLocalDate()
            val e = mt.endDate.toLocalDate()
            today in s..e
        }

        // Log which timetable is being used
        if (activeMulti != null) {
            Log.i(TAG, "📅 Using MULTIPLE TIMETABLE: ${activeMulti.timetable.timeTableName}")
            Log.i(TAG, "   Active from ${activeMulti.startDate} to ${activeMulti.endDate}")
        } else {
            Log.i(TAG, "📅 Using DEFAULT TIMETABLE")
        }

        // Get active timetable range (default if none)
        val range = activeMulti?.timetable?.range ?: user.timetable?.range ?: return false
        val now = Calendar.getInstance()

        // Log all shift windows for debugging
        Log.i(TAG, "🕐 Current time: $currentTimeStr on $todayDayName")
        Log.i(TAG, "📋 Checking ${range.size} shift(s) in timetable:")
        range.forEach { shift ->
            val isThisDayShift = shift.day.equals(todayDayName, ignoreCase = true)
            val marker = if (isThisDayShift) "→" else " "
            Log.i(TAG, "  $marker ${shift.day}: ${shift.start} - ${shift.end} (with ±1h buffer)")
        }

        // ✅ STEP 1: Check if we're inside TODAY's shift
        val todayShift = range.find { it.day.equals(todayDayName, ignoreCase = true) }
        var isInsideShift = false

        if (todayShift?.start != null && todayShift.end != null) {
            isInsideShift = ShiftUtils.isTimeWithinBufferRange(now, todayShift.start, todayShift.end)
            Log.i(TAG, "📅 Today ($todayDayName) shift: ${todayShift.start} - ${todayShift.end} | Result: ${if (isInsideShift) "✅ INSIDE" else "❌ OUTSIDE"}")

            if (isInsideShift) {
                Log.i(TAG, "✅ INSIDE shift window: $todayDayName (${todayShift.start} - ${todayShift.end} with ±1h buffer)")
            }
        } else {
            Log.i(TAG, "📅 Today ($todayDayName) has no shift configured")
        }

        // ✅ STEP 2: If not inside today's shift, check YESTERDAY's overnight shift
        if (!isInsideShift) {
            val yesterdayShift = range.find { it.day.equals(yesterdayDayName, ignoreCase = true) }

            if (yesterdayShift?.start != null && yesterdayShift.end != null) {
                // ✅ CRITICAL: Pass -1 to check yesterday's shift that extends into today
                isInsideShift = ShiftUtils.isTimeWithinBufferRange(now, yesterdayShift.start, yesterdayShift.end, -1)
                Log.i(TAG, "📅 Yesterday ($yesterdayDayName) shift check (overnight): ${yesterdayShift.start} - ${yesterdayShift.end} | Result: ${if (isInsideShift) "✅ INSIDE" else "❌ OUTSIDE"}")

                if (isInsideShift) {
                    Log.i(TAG, "✅ INSIDE shift window: Yesterday's $yesterdayDayName shift extends into today (${yesterdayShift.start} - ${yesterdayShift.end} with ±1h buffer)")
                }
            } else {
                Log.i(TAG, "📅 Yesterday ($yesterdayDayName) has no shift configured")
            }
        }

        if (!isInsideShift) {
            Log.w(TAG, "❌ NOT inside any shift window at $currentTimeStr")
            Log.w(TAG, "💡 Note: Checked both today's shift and yesterday's overnight shift")
        }

        Log.i(TAG, "shouldRunCheck → insideShift=$isInsideShift at ${Utils.getCurrentDateTime()}")
        return isInsideShift
    }

    private fun isMyServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        return manager.getRunningServices(Integer.MAX_VALUE)
            .any { it.service.className == serviceClass.name }
    }

    private fun startMyService(context: Context) {
        val serviceIntent = Intent(context, MyService::class.java).apply {
            action = MyService.ACTION_START
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
        Log.i(TAG, "📲 Service start command sent")
    }

    private fun stopMyService(context: Context) {
        val serviceIntent = Intent(context, MyService::class.java).apply {
            action = MyService.ACTION_STOP
        }
        context.startService(serviceIntent)
        Log.i(TAG, "🛑 Service stop command sent")
    }
}
