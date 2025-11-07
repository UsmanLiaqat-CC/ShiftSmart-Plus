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

                "START_SERVICE" -> {
                    Log.i("AlarmReceiver", "Received START_SERVICE_ALARM")

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

              /*  "CALL_API" -> {
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
                            val lastBoundaryMinute = (currentMinute / 5) * 5

                            // ✅ Always normalize to nearest LOWER 5-min boundary
                            val targetTime = Calendar.getInstance().apply {
                                set(Calendar.MINUTE, lastBoundaryMinute)
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

                            // ✅ STEP 3: Compare normalized times and ensure correct boundary
                            if (lastRecordTime != null && lastRecordTimestamp > 0L) {
                                val normalizedLastTimestamp = Calendar.getInstance().apply {
                                    timeInMillis = lastRecordTimestamp
                                    set(Calendar.SECOND, 0)
                                    set(Calendar.MILLISECOND, 0)
                                }.timeInMillis

                                // Force rounding down to prevent drift (e.g., 21:14 → 21:10)
                                val currentCal = Calendar.getInstance().apply {
                                    set(Calendar.SECOND, 0)
                                    set(Calendar.MILLISECOND, 0)
                                }
                                val currentMinute = currentCal.get(Calendar.MINUTE)
                                val roundedMinute = (currentMinute / 5) * 5
                                currentCal.set(Calendar.MINUTE, roundedMinute)
                                val normalizedCurrentTimestamp = currentCal.timeInMillis

                                val gapMillis = normalizedCurrentTimestamp - normalizedLastTimestamp
                                val minutesDiff = (gapMillis / (1000 * 60)).toInt()

                                Log.i("AlarmReceiver", "⏱️ Gap: $minutesDiff minutes")

                                val remainder = minutesDiff % 5
                                if (remainder != 0) {
                                    Log.w("AlarmReceiver", "⚠️ Misaligned: remainder=$remainder → rescheduling next boundary")

                                    val addMinutes = if (currentMinute % 5 == 0) 5 else (5 - currentMinute % 5)
                                    currentCal.add(Calendar.MINUTE, addMinutes)

                                    val nextSyncTime = currentCal.timeInMillis
                                    val nextSyncTimeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                                        .format(Date(nextSyncTime))

                                    Log.w("AlarmReceiver", "⏭️ Rescheduling to next boundary: $nextSyncTimeStr")
                                    rescheduleAlarmAtSpecificTime(context, nextSyncTime)
                                    return@launch
                                }

                                // ✅ If perfectly aligned and >=5 min gap → perform sync
                                if (minutesDiff >= 5) {
                                    Log.i("AlarmReceiver", "✅ Aligned on boundary — calling API")

                                    val apiIntent = Intent(context, MyService::class.java).apply {
                                        action = MyService.ACTION_CALL_API
                                    }
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                                        context.startForegroundService(apiIntent)
                                    else context.startService(apiIntent)
                                } else {
                                    Log.i("AlarmReceiver", "⏸️ Gap < 5 min → skipping duplicate insert")
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
                }*/


            }

        } catch (e: Exception) {
            Log.e("TAG", "Error in AlarmReceiver onReceive", e)
        }
    }

    companion object {
        /**
         * scheduleNextAlignedAlarm(...)
         * WHEN: After each CALL_API execution or skip.
         * WHAT: Cancels existing CALL_API PI and arms the next exact 5-min boundary.
         */
        @JvmStatic

        fun scheduleNextAlignedAlarm(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val now = Calendar.getInstance().apply {
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            // Round up to next 5-minute boundary
            val nextAligned = ((now / (5 * 60 * 1000)) + 1) * (5 * 60 * 1000)

            val intent = Intent(context, AlarmReceiver::class.java).apply { action = "CALL_API" }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                1234,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            alarmManager.cancel(pendingIntent)

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
                    AlarmManager.AlarmClockInfo(targetTimestamp, showIntent),
                    pendingIntent
                )
                Log.i(
                    "AlarmReceiver",
                    "⏰ Rescheduled CALL_API via setAlarmClock at: ${Date(targetTimestamp)}"
                )
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