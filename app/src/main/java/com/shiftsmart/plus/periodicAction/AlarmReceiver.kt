package com.shiftsmart.plus.periodicAction

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
/*
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
                    // ⚠️ Chain tomorrow so we never depend on another trigger
                    AlarmScheduler.scheduleTomorrowFromPrefs(context)


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

}*/
/*class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        try {
            Log.i("TAG", "AlarmReceiver: onReceive at ${Utils.getCurrentDateTime()}")

            when (intent?.action) {
                "START_SERVICE" -> {
                    Log.i("AlarmReceiver", "Received START_SERVICE_ALARM")

                    // (Optional) wake the screen / bring UI
                    val wakeIntent = Intent(context, WakeUpActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    context.startActivity(wakeIntent)

                    // Start foreground service
                    val serviceIntent = Intent(context, MyService::class.java).apply {
                        action = MyService.ACTION_START
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                    Log.i("AlarmReceiver", "Foreground service started successfully")

                    // Chain tomorrow so we never depend on another trigger
                    AlarmScheduler.scheduleTomorrowFromPrefs(context)
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

                    // ✅ New 5-minute bucket check
                    val lastBucket = (lastCallTime ?: 0) / (5 * 60 * 1000)
                    val currentBucket = currentTime / (5 * 60 * 1000)

                    if (currentBucket != lastBucket) {
                        val user = SharedPref.getInstance(context)?.getUser()

                        // If inside shift and service not running, ensure we schedule Today+Tomorrow
                        user?.let { handleShiftPeriod(context, it) }

                        // Kick API work
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

            // 1) Pick active multiple timetable for today, else default timetable
            val activeMultiTable = user.multipleTimeTables?.find { mt ->
                val start = mt.startDate.toLocalDate()
                val end = mt.endDate.toLocalDate()
                currentDate in start..end
            }

            val effectiveShifts = activeMultiTable?.timetable?.range ?: user.timetable?.range.orEmpty()
            val todayShift = effectiveShifts.find { it.day.equals(today, ignoreCase = true) }

            if (todayShift != null && todayShift.start != null && todayShift.end != null) {
                Log.i("TAG", "Today's Shift -> day:${todayShift.day}, start:${todayShift.start}, end:${todayShift.end}")

                val startCalendar = getCalendarForShift(todayShift.day, todayShift.start, -1)
                val endCalendar = getCalendarForShift(todayShift.day, todayShift.end, 1)

                val now = Calendar.getInstance()

                if (startCalendar != null && endCalendar != null) {
                    // We are INSIDE the window
                    if (now.after(startCalendar) && now.before(endCalendar)) {
                        if (!isServiceRunning(context)) {
                            Log.i("TAG", "Inside window; service NOT running → schedule Today+Tomorrow")
                            val def = user.timetable?.range ?: emptyList()
                            val multi = user.multipleTimeTables ?: emptyList()
                            AlarmScheduler.scheduleTodayAndTomorrow(
                                context = context,
                                defaultShifts = def,
                                multipleTimeTables = multi,
                                reschedulePeriodic = false
                            )
                        } else {
                            Log.i("TAG", "Service already running; no re-schedule")
                        }
                    } else {
                        Log.i("TAG", "Outside window; not scheduling from CALL_API tick")
                    }
                }
            } else {
                Log.i("TAG", "No shift found for today.")
            }
        } catch (e: Exception) {
            Log.e("TAG", "Error in handleShiftPeriod", e)
        }
    }

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

    private fun scheduleNextAlignedAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val now = Calendar.getInstance().timeInMillis
        // Next exact multiple of 5 minutes
        val nextAligned = ((now / (5 * 60 * 1000)) + 1) * (5 * 60 * 1000)

        val intent = Intent(context, AlarmReceiver::class.java).apply { action = "CALL_API" }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            1234,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)

        // Exact fallback if exact alarms aren't allowed on S+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            val showIntent = PendingIntent.getActivity(
                context,
                0,
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:${context.packageName}")
                },
                PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(nextAligned, showIntent),
                pendingIntent
            )
            Log.w("AlarmReceiver", "CALL_API scheduled via setAlarmClock at: ${Date(nextAligned)}")
        } else {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                nextAligned,
                pendingIntent
            )
            Log.d("AlarmReceiver", "Next aligned alarm scheduled at: ${Date(nextAligned)}")
        }
    }
}*/
/**
 * AlarmReceiver
 *  - Handles all alarm actions:
 *      • "START_SERVICE": wake UI (optional), start MyService in foreground, then chain TOMORROW.
 *      • "STOP_SERVICE" : request service to stop.
 *      • "CALL_API"     : 5-min heartbeat → do API work, re-arm next tick, and if we’re inside the
 *                         shift window but service isn’t running, (re)arm Today+Tomorrow.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        try {
            Log.i("TAG", "AlarmReceiver: onReceive at ${Utils.getCurrentDateTime()}")

            when (intent?.action) {
                /**
                 * START_SERVICE
                 * WHEN: Fired by TODAY’s start alarm (rc=1001) or TOMORROW’s start alarm (rc=1101).
                 * WHAT: Optionally wakes UI, starts MyService in foreground, then schedules tomorrow
                 *       so we never depend on another external trigger to keep the chain going.
                 */
                "START_SERVICE" -> {
                    Log.i("AlarmReceiver", "Received START_SERVICE_ALARM")

                    // (Optional) bring UI up / turn screen on
                    val wakeIntent = Intent(context, WakeUpActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    context.startActivity(wakeIntent)

                    // Start the foreground service
                    val serviceIntent = Intent(context, MyService::class.java).apply {
                        action = MyService.ACTION_START
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                    Log.i("AlarmReceiver", "Foreground service started successfully")

                    // Chain TOMORROW (rc=1101/1102)
                    AlarmScheduler.scheduleTomorrowFromPrefs(context)
                }

                /**
                 * STOP_SERVICE
                 * WHEN: Fired by TODAY’s stop alarm (rc=1002) or TOMORROW’s stop alarm (rc=1102).
                 * WHAT: Ask the service to stop itself (service handles teardown).
                 */
                "STOP_SERVICE" -> {
                    Log.i("AlarmReceiver", "Received STOP_SERVICE_ALARM")
                    val stopIntent = Intent(context, MyService::class.java).apply {
                        action = MyService.ACTION_STOP
                    }
                    context.startService(stopIntent)
                }

                /**
                 * CALL_API
                 * WHEN: Every 5 minutes (aligned). One-shot alarm that re-arms itself.
                 * WHAT:
                 *   1) Dedupes by 5-min bucket to avoid double fires.
                 *   2) Ensures if we are inside the shift window but service isn’t running,
                 *      we (re)arm Today+Tomorrow so the service starts immediately and tomorrow is prepared.
                 *   3) Triggers MyService.ACTION_CALL_API to run the periodic task.
                 *   4) Re-schedules the next 5-min tick.
                 */
                "CALL_API" -> {
                    Log.i("AlarmReceiver", "Received CALL_API")

                    val lastCallTime = SharedPref.getInstance(context)?.getLastApiCallTime()
                    val currentTime = System.currentTimeMillis()
                    Log.i("AlarmReceiver", "Last call: $lastCallTime, Current time: $currentTime")

                    // ✅ Deduplicate by 5-minute buckets
                    val lastBucket = (lastCallTime ?: 0) / (5 * 60 * 1000)
                    val currentBucket = currentTime / (5 * 60 * 1000)

                    if (currentBucket != lastBucket) {
                        // If inside the shift and service not running → (re)arm Today+Tomorrow
                        val user = SharedPref.getInstance(context)?.getUser()
                        user?.let { handleShiftPeriod(context, it) }

                        // Kick API work
                        val apiIntent = Intent(context, MyService::class.java).apply {
                            action = MyService.ACTION_CALL_API
                        }
                        context.startService(apiIntent)

                        // Save bucket time and re-arm next tick
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

    /**
     * handleShiftPeriod(...)
     * WHEN: Invoked on each CALL_API tick.
     * WHAT: If NOW is inside the effective window and the service is NOT running,
     *       schedule Today+Tomorrow to (a) start immediately and (b) ensure tomorrow is prepared.
     */
    private fun handleShiftPeriod(
        context: Context,
        user: UserModel
    ) {
        try {
            val today = getCurrentDayName()
            val currentDate = LocalDate.now()

            // 1) Pick active multiple timetable or default timetable
            val activeMultiTable = user.multipleTimeTables?.find { mt ->
                val start = mt.startDate.toLocalDate()
                val end = mt.endDate.toLocalDate()
                currentDate in start..end
            }

            val effectiveShifts = activeMultiTable?.timetable?.range ?: user.timetable?.range.orEmpty()
            val todayShift = effectiveShifts.find { it.day.equals(today, ignoreCase = true) }

            if (todayShift != null && todayShift.start != null && todayShift.end != null) {
                Log.i("TAG", "Today's Shift -> day:${todayShift.day}, start:${todayShift.start}, end:${todayShift.end}")

                val startCalendar = getCalendarForShift(todayShift.day, todayShift.start, -1)
                val endCalendar   = getCalendarForShift(todayShift.day, todayShift.end,   1)

                val now = Calendar.getInstance()
                if (startCalendar != null && endCalendar != null) {
                    // Inside window?
                    if (now.after(startCalendar) && now.before(endCalendar)) {
                        if (!isServiceRunning(context)) {
                            Log.i("TAG", "Inside window; service NOT running → schedule Today+Tomorrow")
                            val def = user.timetable?.range ?: emptyList()
                            val multi = user.multipleTimeTables ?: emptyList()
                            AlarmScheduler.scheduleTodayAndTomorrow(
                                context = context,
                                defaultShifts = def,
                                multipleTimeTables = multi,
                                reschedulePeriodic = false
                            )
                        } else {
                            Log.i("TAG", "Service already running; no re-schedule")
                        }
                    } else {
                        Log.i("TAG", "Outside window; not scheduling from CALL_API tick")
                    }
                }
            } else {
                Log.i("TAG", "No shift found for today.")
            }
        } catch (e: Exception) {
            Log.e("TAG", "Error in handleShiftPeriod", e)
        }
    }

    /**
     * isServiceRunning(...)
     * WHAT: Checks if MyService is listed among running services.
     * NOTE: getRunningServices is limited on newer Android versions; if inaccurate for you,
     *       consider tracking foreground state via a shared flag/notification instead.
     */
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

    /**
     * getShiftsFromSharedPreferences(...)
     * WHAT: Helper to read timetable.range from stored user (unused in current flow; kept for reference).
     */
    private fun getShiftsFromSharedPreferences(context: Context): List<TimeRange> {
        return try {
            SharedPref.getInstance(context)?.getUser()?.timetable?.range ?: emptyList()
        } catch (e: Exception) {
            Log.e("TAG", "Error retrieving shifts from SharedPreferences", e)
            emptyList()
        }
    }

    /**
     * scheduleNextAlignedAlarm(...)
     * WHEN: After each CALL_API execution.
     * WHAT: Cancels existing CALL_API PI and arms the next exact 5-min boundary.
     * NOTE: Uses setAlarmClock fallback on S+ when exact-alarm permission is missing.
     */
    private fun scheduleNextAlignedAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val now = Calendar.getInstance().timeInMillis
        val nextAligned = ((now / (5 * 60 * 1000)) + 1) * (5 * 60 * 1000) // next multiple of 5 min

        val intent = Intent(context, AlarmReceiver::class.java).apply { action = "CALL_API" }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 1234, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            val showIntent = PendingIntent.getActivity(
                context, 0,
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:${context.packageName}")
                },
                PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(nextAligned, showIntent), pendingIntent)
            Log.w("AlarmReceiver", "CALL_API scheduled via setAlarmClock at: ${Date(nextAligned)}")
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextAligned, pendingIntent)
            Log.d("AlarmReceiver", "Next aligned alarm scheduled at: ${Date(nextAligned)}")
        }
    }
}
