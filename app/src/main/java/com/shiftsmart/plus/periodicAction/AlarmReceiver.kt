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
import com.shiftsmart.plus.database.ShiftSmartPlusDatabase
import com.shiftsmart.plus.models.UserModel

import com.shiftsmart.plus.services.MyService
import com.shiftsmart.plus.ui.activities.WakeUpActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.shiftsmart.plus.utils.SharedPref
import com.shiftsmart.plus.utils.ShiftUtils
import com.shiftsmart.plus.utils.Utils
import com.shiftsmart.plus.utils.Utils.toLocalDate
import java.text.SimpleDateFormat

import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.div
import kotlin.text.compareTo
import kotlin.text.format
import kotlin.text.get
import kotlin.text.set
import kotlin.text.toInt
import kotlin.times

/**
 * AlarmReceiver
 *  - Handles all alarm actions with STRICT 5-minute interval enforcement
 *
 *  SHIFT TIMING RULES (±1 HOUR BUFFER):
 *  ═══════════════════════════════════════════════════════════════════════════
 *  • If shift start time is 08:00 → Service starts at 07:00 (1 hour before)
 *  • If shift end time is 18:00 → Service stops at 19:00 (1 hour after)
 *
 *  REGULAR SHIFT EXAMPLE (Monday 08:00 - 18:00):
 *  ─────────────────────────────────────────────
 *  • Service runs: Monday 07:00 - Monday 19:00
 *
 *  OVERNIGHT SHIFT EXAMPLE (Monday 20:00 - Tuesday 02:00):
 *  ───────────────────────────────────────────────────────
 *  • Service runs: Monday 19:00 - Tuesday 03:00
 *  • CRITICAL: End time extends into NEXT DAY
 *
 *  ACTIONS:
 *  ════════
 *  • "START_SERVICE": Wake UI (optional), start MyService in foreground, chain tomorrow's alarms
 *  • "STOP_SERVICE": Request service to stop, chain tomorrow's alarms
 *  • "CALL_API": 5-min heartbeat → STRICT enforcement of exact 5-minute gaps
 *
 *  5-MINUTE INTERVAL ENFORCEMENT:
 *  ══════════════════════════════
 *  • Records MUST be exactly 5 minutes apart (15:10, 15:15, 15:20, ...)
 *  • If alarm triggers at 15:23 (after 15:20), it will SKIP and reschedule to 15:25
 *  • If alarm triggers at 15:24, it will SKIP and reschedule to 15:25
 *  • Always maintains alignment with LAST successful sync time
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        try {
            Log.i("TAG", "AlarmReceiver: onReceive at ${Utils.getCurrentDateTime()}")

            when (intent?.action) {

                "START_SERVICE" -> {
                    Log.i("AlarmReceiver", "Received START_SERVICE_ALARM")

                    // Check if we're inside shift before starting
                    val user = SharedPref.getInstance(context)?.getUser()
                    if (user != null && isInsideShiftWindow(user)) {
                        Log.i("AlarmReceiver", "✅ Inside shift - starting service")

                        val wakeIntent = Intent(context, WakeUpActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        }
                        context.startActivity(wakeIntent)

                        val serviceIntent = Intent(context, MyService::class.java).apply {
                            action = MyService.ACTION_START
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                            context.startForegroundService(serviceIntent)
                        else
                            context.startService(serviceIntent)
                    } else {
                        Log.i("AlarmReceiver", "⏭️ Outside shift window - skipping service start")
                    }

                    AlarmScheduler.scheduleTomorrowFromPrefs(context)
                }

                "STOP_SERVICE" -> {
                    Log.i("AlarmReceiver", "Received STOP_SERVICE_ALARM")

                    val stopIntent = Intent(context, MyService::class.java).apply {
                        action = MyService.ACTION_STOP
                    }
                    context.startService(stopIntent)

                    AlarmScheduler.scheduleTomorrowFromPrefs(context)
                }

                "CALL_API" -> {
                    Log.i("AlarmReceiver", "🔔 Received CALL_API at ${Utils.getCurrentDateTime()}")

                    val user = SharedPref.getInstance(context)?.getUser()
                    if (user == null) {
                        Log.w("AlarmReceiver", "❌ No user found; skipping CALL_API")
                        return
                    }

                    // ✅ Check if we're inside shift window before processing
                    if (!isInsideShiftWindow( user)) {
                        Log.i("AlarmReceiver", "⏭️ Outside shift window - skipping CALL_API")
                        scheduleNextAlignedAlarm(context)
                        return
                    }

                    Log.i("AlarmReceiver", "✅ Inside shift window - processing CALL_API")

                    // ✅ CRITICAL: Check if service is running - if not, restart it!
                    val isServiceRunning = Utils.isServiceRunning(context, MyService::class.java)
                    if (!isServiceRunning) {
                        Log.w("AlarmReceiver", "🚨 Service NOT running during CALL_API - Restarting service!")
                        val startIntent = Intent(context, MyService::class.java).apply {
                            action = MyService.ACTION_START
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(startIntent)
                        } else {
                            context.startService(startIntent)
                        }
                        // Give service time to start
                        Thread.sleep(1500)
                    }

                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val sharedPref = SharedPref.getInstance(context)

                            val currentTime = Calendar.getInstance()
                            val currentMinute = currentTime.get(Calendar.MINUTE)
                            val currentSecond = currentTime.get(Calendar.SECOND)

                            Log.i(
                                "AlarmReceiver",
                                "📍 Processing at: ${Utils.getCurrent24HourTime()} (minute: $currentMinute, second: $currentSecond)"
                            )

                            // ✅ STRICT BOUNDARY CHECK: Only process if current time is on 5-minute boundary
                            if (currentMinute % 5 != 0) {
                                Log.w("AlarmReceiver", "⏭️ Current time NOT on 5-min boundary (${currentMinute}m) → skipping and rescheduling")

                                // Calculate next 5-minute boundary
                                val nextBoundaryMinute = ((currentMinute / 5) + 1) * 5
                                val nextBoundaryTime = Calendar.getInstance().apply {
                                    if (nextBoundaryMinute >= 60) {
                                        add(Calendar.HOUR_OF_DAY, 1)
                                        set(Calendar.MINUTE, 0)
                                    } else {
                                        set(Calendar.MINUTE, nextBoundaryMinute)
                                    }
                                    set(Calendar.SECOND, 0)
                                    set(Calendar.MILLISECOND, 0)
                                }

                                val nextTimeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(nextBoundaryTime.time)
                                Log.i("AlarmReceiver", "⏰ Scheduling next alarm at: $nextTimeStr")

                                // Schedule alarm at the exact next 5-minute boundary and CANCEL any existing ones
                                scheduleAtExactTime(context, nextBoundaryTime.timeInMillis)
                                return@launch
                            }

                            Log.i("AlarmReceiver", "✅ On 5-minute boundary - proceeding with API call")

                            // ✅ Normalize to current 5-min boundary for record timestamp
                            val targetTime = Calendar.getInstance().apply {
                                set(Calendar.MINUTE, currentMinute)
                                set(Calendar.SECOND, 0)
                                set(Calendar.MILLISECOND, 0)
                            }

                            // ✅ STEP 1: Get last recorded timestamp from SharedPref only
                            val lastSyncTimestamp = sharedPref?.getLastSyncTimestamp() ?: 0L

                            if (lastSyncTimestamp == 0L) {
                                // ✅ SCENARIO 1: No last record → Fresh start
                                Log.i("AlarmReceiver", "🆕 No previous record found — starting fresh")

                                val apiIntent = Intent(context, MyService::class.java).apply {
                                    action = MyService.ACTION_CALL_API
                                }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                                    context.startForegroundService(apiIntent)
                                else context.startService(apiIntent)

                            } else {
                                // ✅ SCENARIO 2: Last record exists - check gap
                                val currentTimestamp = targetTime.timeInMillis
                                val gapMillis = currentTimestamp - lastSyncTimestamp
                                val minutesDiff = (gapMillis / (60 * 1000)).toInt()

                                Log.i("AlarmReceiver", "⏱️ Last sync: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(lastSyncTimestamp))}")
                                Log.i("AlarmReceiver", "⏱️ Current: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(currentTimestamp))}")
                                Log.i("AlarmReceiver", "⏱️ Gap: $minutesDiff minutes")

                                when {
                                    minutesDiff < 5 -> {
                                        // Gap is less than 5 minutes - skip
                                        Log.w("AlarmReceiver", "⏸️ Gap too small ($minutesDiff min < 5) → skipping")
                                    }

                                    minutesDiff % 5 == 0 -> {
                                        // Gap is exactly a multiple of 5 (5, 10, 15, 20...) - call API
                                        Log.i("AlarmReceiver", "✅ Gap is valid multiple of 5 ($minutesDiff min) — calling API")

                                        val apiIntent = Intent(context, MyService::class.java).apply {
                                            action = MyService.ACTION_CALL_API
                                        }
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                                            context.startForegroundService(apiIntent)
                                        else context.startService(apiIntent)
                                    }

                                    else -> {
                                        // Gap is >= 5 but NOT a multiple of 5 (e.g., 6, 7, 11, 13...)
                                        // Skip and schedule at next aligned time
                                        Log.w("AlarmReceiver", "⚠️ Gap not aligned ($minutesDiff min) → skipping to next aligned time")

                                        // Calculate next valid time: next multiple of 5 from last sync
                                        val nextValidMinutes = ((minutesDiff / 5) + 1) * 5
                                        val nextValidTime = lastSyncTimestamp + (nextValidMinutes * 60 * 1000)

                                        val nextTimeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(nextValidTime))
                                        Log.i("AlarmReceiver", "⏭️ Next aligned time: $nextTimeStr (${nextValidMinutes} min from last sync)")

                                        // Schedule alarm at the next aligned time
                                        scheduleAtExactTime(context, nextValidTime)
                                        return@launch
                                    }
                                }
                            }


                            // ✅ Always reschedule next alarm aligned to 5-min boundary
                            scheduleNextAlignedAlarm(context)

                        } catch (e: Exception) {
                            Log.e("AlarmReceiver", "❌ Error in CALL_API", e)
                            scheduleNextAlignedAlarm(context)
                        }
                    }
                }


            }

        } catch (e: Exception) {
            Log.e("TAG", "Error in AlarmReceiver onReceive", e)
        }
    }

    companion object {
        /**
         * Check if current time is within shift window (with ±1 hour buffer)
         * If shift is 08:00-18:00, service runs 07:00-19:00
         * If shift is overnight 20:00-04:00, service runs 19:00-05:00 (next day)
         */
        @JvmStatic
        fun isInsideShiftWindow( user: UserModel): Boolean {
            return try {
                val today = LocalDate.now()
                val activeMulti = user.multipleTimeTables?.find { mt ->
                    val s = mt.startDate.toLocalDate()
                    val e = mt.endDate.toLocalDate()
                    today in s..e
                }
                val effectiveRange = activeMulti?.timetable?.range ?: user.timetable?.range ?: return false

                val now = Calendar.getInstance()

                // ✅ Check today's shift
                val todayName = now.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.ENGLISH) ?: ""
                val todayShift = effectiveRange.find { it.day.equals(todayName, ignoreCase = true) }

                var isWithinShift = false

                if (todayShift?.start != null && todayShift.end != null) {
                    isWithinShift = ShiftUtils.isTimeWithinBufferRange(
                        now,
                        todayShift.start,
                        todayShift.end,
                        0 // Today
                    )
                    if (isWithinShift) {
                        Log.i("AlarmReceiver", "✅ Within today's shift (${todayName})")
                        return true
                    }
                }

                // ✅ Check yesterday's shift (for overnight shifts)
                val yesterdayCalendar = now.clone() as Calendar
                yesterdayCalendar.add(Calendar.DAY_OF_YEAR, -1)
                val yesterdayName = yesterdayCalendar.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.ENGLISH) ?: ""
                val yesterdayShift = effectiveRange.find { it.day.equals(yesterdayName, ignoreCase = true) }

                if (yesterdayShift?.start != null && yesterdayShift.end != null) {
                    isWithinShift = ShiftUtils.isTimeWithinBufferRange(
                        now,
                        yesterdayShift.start,
                        yesterdayShift.end,
                        -1 // Yesterday
                    )
                    if (isWithinShift) {
                        Log.i("AlarmReceiver", "✅ Within yesterday's overnight shift (${yesterdayName})")
                        return true
                    }
                }

                Log.i("AlarmReceiver", "❌ Not within any shift window")
                false

            } catch (e: Exception) {
                Log.e("AlarmReceiver", "❌ Error checking shift window", e)
                false
            }
        }

        /**
         * scheduleNextAlignedAlarm(...)
         * WHEN: After each CALL_API execution or skip.
         * WHAT: Cancels existing CALL_API PI and arms the next exact 5-min boundary.
         *       If last sync exists, schedules exactly 5 minutes from it.
         *       Otherwise, rounds up to next 5-min boundary from current time.
         */
        @JvmStatic
        fun scheduleNextAlignedAlarm(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val sharedPref = SharedPref.getInstance(context)
            val lastSyncTimestamp = sharedPref?.getLastSyncTimestamp() ?: 0L

            val nextAligned = if (lastSyncTimestamp > 0L) {
                // ✅ Schedule exactly 5 minutes from last sync
                lastSyncTimestamp + (5 * 60 * 1000)
            } else {
                // ✅ No last sync - round up to next 5-minute boundary
                val now = System.currentTimeMillis()
                ((now / (5 * 60 * 1000)) + 1) * (5 * 60 * 1000)
            }

            val intent = Intent(context, AlarmReceiver::class.java).apply { action = "CALL_API" }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                1234,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            alarmManager.cancel(pendingIntent)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        nextAligned,
                        pendingIntent
                    )
                } else {
                    // Fallback if permission not granted
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        nextAligned,
                        pendingIntent
                    )
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    nextAligned,
                    pendingIntent
                )
            }

            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(nextAligned))
            Log.i("AlarmReceiver", "⏰ Next CALL_API alarm scheduled at: $timeStr (${Date(nextAligned)})")
        }

        /**
         * scheduleAtExactTime(...)
         * WHEN: When alarm triggers but is not on 5-minute boundary
         * WHAT: Cancels ALL existing CALL_API alarms and schedules ONE alarm at exact target time
         * WHY: Prevents infinite retriggering loop
         */
        @JvmStatic
        fun scheduleAtExactTime(context: Context, targetTimestamp: Long) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, AlarmReceiver::class.java).apply { action = "CALL_API" }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                1234,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // ✅ CRITICAL: Cancel any existing alarms to prevent retriggering
            alarmManager.cancel(pendingIntent)
            Log.i("AlarmReceiver", "🚫 Cancelled existing CALL_API alarms")

            // ✅ Schedule ONE alarm at exact target time
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        targetTimestamp,
                        pendingIntent
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        targetTimestamp,
                        pendingIntent
                    )
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    targetTimestamp,
                    pendingIntent
                )
            }

            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(targetTimestamp))
            Log.i("AlarmReceiver", "✅ Single CALL_API alarm scheduled at: $timeStr")
        }

        /**
         * rescheduleAlarmAtSpecificTime(...)
         * WHEN: When current alarm time is not aligned with last sync time (not a multiple of 5 minutes)
         * WHAT: Cancels existing CALL_API PI and schedules alarm at the exact time that aligns with last sync
         */
        @JvmStatic
        fun rescheduleAlarmAtSpecificTime(context: Context, targetTimestamp: Long) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, AlarmReceiver::class.java).apply { action = "CALL_API" }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                1234,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            alarmManager.cancel(pendingIntent)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Log.i(
                    "AlarmReceiver",
                    "⏰ Rescheduled CALL_API via setAlarmClock at: ${Date(targetTimestamp)}"
                )

                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        targetTimestamp,
                        pendingIntent
                    )
                } else {
                    // Fallback if permission not granted
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        targetTimestamp,
                        pendingIntent
                    )
                }


            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    targetTimestamp,
                    pendingIntent
                )
                Log.i(
                    "AlarmReceiver",
                    "⏰ Rescheduled CALL_API (aligned) at: ${Date(targetTimestamp)}"
                )
            }
        }

    }
}

