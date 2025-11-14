package com.shiftsmart.plus.periodicAction

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.shiftsmart.plus.models.UserModel
import com.shiftsmart.plus.services.MyService
import com.shiftsmart.plus.utils.AttendanceSyncManager
import com.shiftsmart.plus.utils.Constants.TAG
import com.shiftsmart.plus.utils.SharedPref
import com.shiftsmart.plus.utils.ShiftUtils
import com.shiftsmart.plus.utils.Utils
import com.shiftsmart.plus.utils.Utils.getCurrentDayName
import com.shiftsmart.plus.utils.Utils.toLocalDate
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Calendar
import java.util.Locale

/**
 * ShiftStatusWorker - DUAL REDUNDANCY FALLBACK
 * ══════════════════════════════════════════════
 *
 * PURPOSE: Acts as a backup when AlarmManager fails to trigger on some devices
 *
 * SHIFT TIMING WITH ±1 HOUR BUFFER:
 * ─────────────────────────────────
 * • If shift is 08:00-18:00 → Check window is 07:00-19:00
 * • If shift is 20:00-02:00 (overnight) → Check window is 19:00-03:00 (next day)
 *
 * HOW IT WORKS:
 * ────────────
 * 1. Scheduled every 15 minutes by WorkManager (Android minimum)
 * 2. Checks if current time is within shift window (using ShiftUtils.isTimeWithinBufferRange)
 * 3. If inside shift AND service not running → Start service
 * 4. If outside shift AND service running → Stop service
 *
 * DUAL APPROACH:
 * ──────────────
 * • PRIMARY: AlarmManager schedules at exact shift start time (shift - 1 hour)
 * • FALLBACK: This worker runs every 15min, checks shift window, starts if missed
 *
 * EXAMPLE SCENARIO:
 * ────────────────
 * Shift: Monday 08:00 - 18:00
 * • AlarmManager should fire: Monday 07:00 (shift start - 1 hour)
 * • If AlarmManager fails (device killed it):
 *   - Worker runs at 07:15, detects inside window (07:00-19:00), starts service
 *   - Worker runs at 07:30, detects service already running, no action
 * • Service stops: Monday 19:00 (shift end + 1 hour)
 */
class ShiftStatusWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val user = SharedPref.getInstance(context)?.getUser() ?: return Result.success()

        val inShift = shouldRunCheck(user)
        val isRunning = isMyServiceRunning(context, MyService::class.java)

        Log.i("ShiftStatusWorker", "🟢 In shift ${inShift} --->🔄 Service running ${isRunning} at:${Utils.getCurrentDateTime()}")
        when {
           /* inShift && !isRunning -> {
                Log.i("ShiftStatusWorker", "🟢 In shift → Start service")
                val i = Intent(context, MyService::class.java).apply { action = MyService.ACTION_START }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
                else context.startService(i)
            }

            inShift && isRunning -> {
                Log.i("ShiftStatusWorker", "🔄 In shift → Refresh service")
                val i = Intent(context, MyService::class.java).apply { action = MyService.ACTION_CALL_API }
                context.startService(i)
            }

            !inShift && isRunning -> {
                Log.i("ShiftStatusWorker", "🔴 Out of shift → Stop service")
                val i = Intent(context, MyService::class.java).apply { action = MyService.ACTION_STOP }
                context.startService(i)
            }*/

            inShift -> {
                Log.i("ShiftStatusWorker", "🟢 In shift → Start service")
                val i = Intent(context, MyService::class.java).apply { action = MyService.ACTION_START }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
                else context.startService(i)
            }


            !inShift -> {
                Log.i("ShiftStatusWorker", "🔴 Out of shift → Stop service")
                val i = Intent(context, MyService::class.java).apply { action = MyService.ACTION_STOP }
                context.startService(i)
            }

            else -> {
                Log.i("ShiftStatusWorker", "⚪ Out of shift & service not running → No action")
            }
        }

        return Result.success()
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

}
