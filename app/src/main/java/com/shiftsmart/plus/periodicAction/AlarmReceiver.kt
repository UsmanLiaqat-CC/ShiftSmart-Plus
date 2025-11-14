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
                    if (user != null && isInsideShiftWindow(context, user)) {
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

                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val db = ShiftSmartPlusDatabase.getInstance(context)
                            val dao = db.dbDao()
                            val sharedPref = SharedPref.getInstance(context)

                            val currentTime = Calendar.getInstance()
                            val currentMinute = currentTime.get(Calendar.MINUTE)

                            // ✅ STRICT: Only process if current minute is EXACTLY on a 5-minute boundary
                            if (currentMinute % 5 != 0) {
                                Log.w("AlarmReceiver", "⏭️ Current time NOT on 5-min boundary (${currentMinute}m) → skipping and rescheduling")
                                scheduleNextAlignedAlarm(context)
                                return@launch
                            }

                            // ✅ Always normalize to current 5-min boundary
                            val targetTime = Calendar.getInstance().apply {
                                set(Calendar.MINUTE, currentMinute)
                                set(Calendar.SECOND, 0)
                                set(Calendar.MILLISECOND, 0)
                            }

                            val targetLocalTime = LocalTime.of(
                                targetTime.get(Calendar.HOUR_OF_DAY),
                                targetTime.get(Calendar.MINUTE),
                                0
                            )

                            Log.i(
                                "AlarmReceiver",
                                "📍 Target 5-min boundary: $targetLocalTime (Current: ${Utils.getCurrent24HourTime()})"
                            )

                            // ✅ STEP 1: Ensure current time is within active shift
                            val today = LocalDate.now()
                            val activeMulti = user.multipleTimeTables?.find { mt ->
                                val s = mt.startDate.toLocalDate()
                                val e = mt.endDate.toLocalDate()
                                today in s..e
                            }
                            val effectiveRange = activeMulti?.timetable?.range ?: user.timetable?.range

                            if (effectiveRange != null) {
                                val todayName = targetTime.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.ENGLISH) ?: ""
                                val yesterdayCalendar = targetTime.clone() as Calendar
                                yesterdayCalendar.add(Calendar.DAY_OF_YEAR, -1)
                                val yesterdayName = yesterdayCalendar.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.ENGLISH) ?: ""

                                val todayShift = effectiveRange.find { it.day.equals(todayName, ignoreCase = true) }
                                val yesterdayShift = effectiveRange.find { it.day.equals(yesterdayName, ignoreCase = true) }

                                var isWithinShift = false
                                if (todayShift?.start != null && todayShift.end != null) {
                                    isWithinShift = com.shiftsmart.plus.utils.ShiftUtils.isTimeWithinBufferRange(
                                        targetTime,
                                        todayShift.start,
                                        todayShift.end
                                    )
                                }
                                if (!isWithinShift && yesterdayShift?.start != null && yesterdayShift.end != null) {
                                    isWithinShift = com.shiftsmart.plus.utils.ShiftUtils.isTimeWithinBufferRange(
                                        targetTime,
                                        yesterdayShift.start,
                                        yesterdayShift.end,
                                        -1
                                    )
                                }

                                if (!isWithinShift) {
                                    Log.i("AlarmReceiver", "⏭️ Outside shift period - skipping record insertion")
                                    scheduleNextAlignedAlarm(context)
                                    return@launch
                                }

                                Log.i("AlarmReceiver", "✅ Within shift period - proceeding")
                            }

                            // ✅ STEP 2: Get last recorded timestamp (DB → SharedPref fallback)
                            val lastSyncTimestamp = sharedPref?.getLastSyncTimestamp() ?: 0L
                            val lastSyncDateTime = sharedPref?.getLastSyncDateTime()
                            val latestDefaultRecord = dao.getLatestDefaultRecord(user._id.toString())

                            val (lastRecordTime, lastRecordTimestamp) = when {
                                latestDefaultRecord?.localTime != null && latestDefaultRecord.time != null -> {
                                    try {
                                        val time = Utils.parseFlexibleTime(latestDefaultRecord.localTime)
                                        val utcFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                                        utcFormatter.timeZone = TimeZone.getTimeZone("UTC")
                                        val utcDate = utcFormatter.parse(latestDefaultRecord.time)

                                        if (time != null && utcDate != null) {
                                            val localCal = Calendar.getInstance().apply { timeInMillis = utcDate.time }
                                            Log.i("AlarmReceiver", "📌 Using last DB record: ${latestDefaultRecord.localTime}")
                                            Pair(time, localCal.timeInMillis)
                                        } else Pair(null, 0L)
                                    } catch (e: Exception) {
                                        Log.e("AlarmReceiver", "Failed to parse DB record: ${e.message}")
                                        Pair(null, 0L)
                                    }
                                }

                                lastSyncTimestamp > 0L -> {
                                    val lastCal = Calendar.getInstance().apply { timeInMillis = lastSyncTimestamp }
                                    val time = LocalTime.of(lastCal.get(Calendar.HOUR_OF_DAY), lastCal.get(Calendar.MINUTE), 0)
                                    Log.i("AlarmReceiver", "📌 Using SharedPref fallback: $lastSyncDateTime")
                                    Pair(time, lastSyncTimestamp)
                                }

                                else -> Pair(null, 0L)
                            }

// ✅ STEP 3: Strict 5-minute gap enforcement
                            if (lastRecordTime != null && lastRecordTimestamp > 0L) {
                                // Calculate exact time difference
                                val currentTimestamp = targetTime.timeInMillis
                                val gapMillis = currentTimestamp - lastRecordTimestamp
                                val minutesDiff = (gapMillis / (60 * 1000)).toInt()

                                Log.i("AlarmReceiver", "⏱️ Last sync: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(lastRecordTimestamp))}")
                                Log.i("AlarmReceiver", "⏱️ Current: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(currentTimestamp))}")
                                Log.i("AlarmReceiver", "⏱️ Gap: $minutesDiff minutes")

                                // ✅ STRICT: Only proceed if gap is EXACTLY a multiple of 5 minutes AND >= 5
                                if (minutesDiff >= 5 && minutesDiff % 5 == 0) {
                                    Log.i("AlarmReceiver", "✅ Gap is valid (${minutesDiff} min) — calling API")

                                    val apiIntent = Intent(context, MyService::class.java).apply {
                                        action = MyService.ACTION_CALL_API
                                    }
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                                        context.startForegroundService(apiIntent)
                                    else context.startService(apiIntent)
                                } else if (minutesDiff < 5) {
                                    Log.w("AlarmReceiver", "⏸️ Gap too small ($minutesDiff min < 5) → skipping")
                                } else {
                                    // Gap is not a multiple of 5 - need to realign
                                    Log.w("AlarmReceiver", "⚠️ Gap not multiple of 5 ($minutesDiff min) → realigning")

                                    // Calculate next valid time (multiple of 5 from last sync)
                                    val nextValidMinutes = ((minutesDiff / 5) + 1) * 5
                                    val nextValidTime = lastRecordTimestamp + (nextValidMinutes * 60 * 1000)

                                    Log.w("AlarmReceiver", "⏭️ Rescheduling to: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(nextValidTime))}")
                                    rescheduleAlarmAtSpecificTime(context, nextValidTime)
                                    return@launch
                                }
                            } else {
                                // ✅ No previous record → insert first record
                                Log.i("AlarmReceiver", "🆕 No previous record found — inserting first one")
                                val apiIntent = Intent(context, MyService::class.java).apply {
                                    action = MyService.ACTION_CALL_API
                                }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                                    context.startForegroundService(apiIntent)
                                else context.startService(apiIntent)
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
        private fun isInsideShiftWindow(context: Context, user: com.shiftsmart.plus.models.UserModel): Boolean {
            return try {
                val today = LocalDate.now()
                val activeMulti = user.multipleTimeTables?.find { mt ->
                    val s = mt.startDate.toLocalDate()
                    val e = mt.endDate.toLocalDate()
                    today in s..e
                }
                val effectiveRange = activeMulti?.timetable?.range ?: user.timetable?.range ?: return false

                val todayName = Utils.getCurrentDayName()
                val todayShift = effectiveRange.find { it.day.equals(todayName, ignoreCase = true) }

                if (todayShift?.start == null || todayShift.end == null) return false

                // ✅ Use ShiftUtils to apply ±1 hour buffer
                val now = Calendar.getInstance()
                ShiftUtils.isTimeWithinBufferRange(
                    now,
                    todayShift.start,
                    todayShift.end
                )
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