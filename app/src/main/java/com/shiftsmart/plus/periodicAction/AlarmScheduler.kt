package com.shiftsmart.plus.periodicAction

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.shiftsmart.plus.models.MultipleTimeTable
import com.shiftsmart.plus.models.TimeRange
import com.shiftsmart.plus.services.MyService
import com.shiftsmart.plus.utils.SharedPref
import com.shiftsmart.plus.utils.Utils
import com.shiftsmart.plus.utils.Utils.getCalendarForShift
import com.shiftsmart.plus.utils.Utils.getCurrentDayName
import com.shiftsmart.plus.utils.Utils.toLocalDate
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Date

/**
 * AlarmScheduler
 *  - Picks the right shift window (multiple timetable if active; otherwise default timetable).
 *  - Schedules:
 *      • START one hour before shift start
 *      • STOP  one hour after  shift end
 *  - If currently inside the window, starts service immediately and only schedules STOP.
 *  - Also provides helpers to schedule TOMORROW’s start/stop using distinct request codes (1101/1102)
 *    so today’s alarms (1001/1002) aren’t overwritten.
 *  - Periodic 5-min CALL_API ticker: can be (re)scheduled and is always deduped.
 */
object AlarmScheduler {
    private const val TAG = "AlarmScheduler"

    /**
     * scheduleAlarms(...)
     *
     * WHEN: Call on login, after FCM user/timetable updates, or any time you want to (re)arm today.
     * WHAT: Schedules TODAY’s START/STOP for the effective shift.
     *  - If NOW is inside window: start service immediately and only schedule STOP.
     *  - Else: schedule both START and STOP.
     * SIDE: Optionally (re)schedules the 5-min CALL_API alarm (one-shot; your receiver re-arms).
     */
    fun scheduleAlarms(
        context: Context,
        defaultShifts: List<TimeRange>,
        multipleTimeTables: List<MultipleTimeTable>,
        reschedulePeriodic: Boolean = true
    ) {
        Log.i(TAG, "scheduleAlarms: ${Utils.getCurrentDateTime()}")

        val today = getCurrentDayName()
        val currentDate = LocalDate.now()

        // 1) Choose active multiple timetable for TODAY (falls back to default if none)
        val activeMultiTable = multipleTimeTables.find { mt ->
            val startDate = mt.startDate.toLocalDate()
            val endDate = mt.endDate.toLocalDate()
            currentDate in startDate..endDate
        }
        val effectiveRange = activeMultiTable?.timetable?.range ?: defaultShifts
        if (activeMultiTable != null) {
            Log.i(TAG, "Using multipleTimeTable: ${activeMultiTable.timetable.timeTableName}")
        } else {
            Log.i(TAG, "Using default timetable")
        }

        // 2) Pull TODAY’s shift
        val todayShift = effectiveRange.find { it.day.equals(today, ignoreCase = true) }

        if (todayShift != null && todayShift.start != null && todayShift.end != null) {
            Log.i(TAG, "Today's Shift -> day:${todayShift.day}, start:${todayShift.start}, end:${todayShift.end}")

            // -1h for START, +1h for STOP
            val startCalendar = getCalendarForShift(todayShift.day, todayShift.start, -1)
            val endCalendar   = getCalendarForShift(todayShift.day, todayShift.end,   1)

            Log.i(TAG, "startCalendar: ${startCalendar?.time} --> endCalendar: ${endCalendar?.time}")

            if (startCalendar != null && endCalendar != null) {
                // Normalize overnight (e.g., end past midnight)
                if (endCalendar.timeInMillis <= startCalendar.timeInMillis) {
                    endCalendar.add(Calendar.DAY_OF_YEAR, 1)
                }

                val now = System.currentTimeMillis()

                // Always clear prior TODAY start/stop alarms (rc=1001/1002)
                cancelServiceAlarm(context, true)
                cancelServiceAlarm(context, false)

                if (now in startCalendar.timeInMillis until endCalendar.timeInMillis) {
                    // Inside window → start immediately; only schedule STOP
                    Log.i(TAG, "Inside window → starting service NOW, scheduling STOP at ${endCalendar.time}")
                    startServiceNow(context)
                    scheduleService(context, endCalendar, false)
                } else {
                    // Outside window → schedule both START and STOP
                    Log.i(TAG, "Outside window → scheduling START ${startCalendar.time} and STOP ${endCalendar.time}")
                    scheduleService(context, startCalendar, true)
                    scheduleService(context, endCalendar, false)
                }

                if (reschedulePeriodic) {
                    // Schedules one-shot CALL_API aligned to next 5-min mark (receiver re-arms)
                    schedulePeriodicAlarm(context)
                }
            }
        } else {
            Log.i(TAG, "No shift found for today.")
        }
    }

    /**
     * startServiceNow(...)
     * WHEN: We’re already inside the active window and must start immediately (no waiting).
     * WHAT: Starts MyService in foreground (O+) or normal start (pre-O).
     * WHO: Called internally by scheduleAlarms/scheduleDay when inside window.
     */
    private fun startServiceNow(context: Context) {
        val intent = Intent(context, MyService::class.java).apply { action = "START_SERVICE" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
        else context.startService(intent)
    }

    /**
     * cancelServiceAlarm(...)
     * WHEN: Before scheduling TODAY’s start/stop to avoid duplicates.
     * WHAT: Cancels TODAY’s START/STOP PendingIntents (request codes 1001/1002).
     */
    private fun cancelServiceAlarm(context: Context, isStart: Boolean) {
        val intent = Intent(context, MyService::class.java).apply {
            action = if (isStart) "START_SERVICE" else "STOP_SERVICE"
        }
        val requestCode = if (isStart) 1001 else 1002
        val pi = PendingIntent.getService(
            context, requestCode, intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pi != null) {
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi)
            Log.i(TAG, "Canceled ${if (isStart) "start" else "stop"} alarm")
        }
    }

    /**
     * schedulePeriodicAlarm(...)
     * WHEN: After (re)arming alarms if you want the 5-min CALL_API heartbeat to persist.
     * WHAT: Cancels any existing CALL_API PI and (re)schedules a one-shot exact alarm aligned
     *       to next 5-min mark. Your AlarmReceiver re-arms the next one each tick.
     */
    fun schedulePeriodicAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // Cancel any existing CALL_API first (rc=1234)
        val cancelIntent = Intent(context, AlarmReceiver::class.java).apply { action = "CALL_API" }
        val cancelPendingIntent = PendingIntent.getBroadcast(
            context, 1234, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(cancelPendingIntent)
        Log.i(TAG, "Canceled any existing CALL_API alarm before scheduling a new one")

        // Exact-alarm permission gate (S+); you already redirect to settings elsewhere if needed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            Log.w(TAG, "Exact alarm permission not granted. Redirecting to settings.")
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            return
        }

        // Arm next aligned CALL_API
        val intent = Intent(context, AlarmReceiver::class.java).apply { action = "CALL_API" }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 1234, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerTime = getNextFiveMinuteAlignedTime()
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
        Log.i(TAG, "Scheduled CALL_API alarm at ${Date(triggerTime)}")
    }

    /**
     * scheduleService(...)
     * WHEN: Used for TODAY’s start (rc=1001) / stop (rc=1002).
     * WHAT: Schedules exact alarms; NOTE currently acquires a wake lock at *schedule* time,
     *       which is generally not needed — prefer holding a short PARTIAL_WAKE_LOCK when the alarm
     *       actually fires (in receiver/service).
     */
    private fun scheduleService(context: Context, calendar: Calendar, isStart: Boolean) {
        Log.i(TAG, "Scheduling Service at ${calendar.time}, isStart: $isStart")
        try {
            val intent = Intent(context, MyService::class.java).apply {
                action = if (isStart) "START_SERVICE" else "STOP_SERVICE"
            }
            val requestCode = if (isStart) 1001 else 1002
            val pendingIntent = PendingIntent.getService(
                context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE
            )

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)

            Log.i(TAG, "Scheduled ${if (isStart) "start" else "stop"} service at: ${calendar.time}")

            // ⛔️ Consider removing this: wake lock at schedule-time does not help at fire-time.
            // Prefer acquiring/releasing in AlarmReceiver/MyService when the alarm actually fires.
            acquireWakeLock(context)
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling service", e)
        }
    }

    /**
     * acquireWakeLock(...)
     * WHEN: Called by scheduleService() right now; recommended to MOVE to receiver/service at fire-time.
     * WHAT: Briefly wakes the screen for 10s. Consider using PARTIAL_WAKE_LOCK at fire-time instead.
     */
    private fun acquireWakeLock(context: Context) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "MyApp::AlarmFullWakeLock"
        )
        if (!wakeLock.isHeld) {
            wakeLock.acquire(10 * 1000L)
            Log.i("AlarmScheduler", "Wake lock acquired and screen turned on.")
        }
    }

    /**
     * getNextFiveMinuteAlignedTime()
     * WHAT: Returns epoch millis for the next exact 5-min boundary from now (sec/ms = 0).
     * WHO: Used by schedulePeriodicAlarm().
     */
    private fun getNextFiveMinuteAlignedTime(): Long {
        val now = Calendar.getInstance()
        val minutes = now.get(Calendar.MINUTE)
        val extra = 5 - (minutes % 5)
        now.add(Calendar.MINUTE, extra)
        now.set(Calendar.SECOND, 0)
        now.set(Calendar.MILLISECOND, 0)
        return now.timeInMillis
    }

    /**
     * getCalendarForDate(...)
     * WHAT: Build a Calendar for a specific LocalDate + "HH:mm" time and apply ±offset hours.
     * WHO: Used by scheduleDay()/tomorrow helpers.
     */
    private fun getCalendarForDate(date: LocalDate, time: String, offsetHours: Int): Calendar {
        val (hh, mm) = time.split(":").map { it.toInt() }
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, date.year)
            set(Calendar.MONTH, date.monthValue - 1)
            set(Calendar.DAY_OF_MONTH, date.dayOfMonth)
            set(Calendar.HOUR_OF_DAY, hh)
            set(Calendar.MINUTE, mm)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.HOUR_OF_DAY, offsetHours)
        }
    }

    /**
     * scheduleDay(...)
     * WHEN: Internal helper to schedule a specific DATE’s start/stop from a timetable.
     * WHAT: Picks the DATE’s effective timetable; schedules either (START+STOP) or immediate start + STOP.
     * NOTE: Cancels TODAY’s start/stop (1001/1002) when used for TODAY; for TOMORROW, prefer the
     *       1101/1102 variants via scheduleServiceWithCode().
     */
    private fun scheduleDay(
        context: Context,
        date: LocalDate,
        defaultShifts: List<TimeRange>,
        multipleTimeTables: List<MultipleTimeTable>,
        allowImmediateStartIfInsideWindow: Boolean
    ) {
        val activeMulti = multipleTimeTables.find { mt ->
            date in mt.startDate.toLocalDate()..mt.endDate.toLocalDate()
        }
        val dayName = date.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
        val range = activeMulti?.timetable?.range ?: defaultShifts
        val shift = range.find { it.day.equals(dayName, ignoreCase = true) }

        if (shift?.start != null && shift.end != null) {
            val startCal = getCalendarForDate(date, shift.start, -1)  // -1h
            val endCal   = getCalendarForDate(date, shift.end,   1)  // +1h
            if (endCal.timeInMillis <= startCal.timeInMillis) endCal.add(Calendar.DAY_OF_YEAR, 1)

            val now = System.currentTimeMillis()
            cancelServiceAlarm(context, true)
            cancelServiceAlarm(context, false)

            if (allowImmediateStartIfInsideWindow && now in startCal.timeInMillis until endCal.timeInMillis) {
                startServiceNow(context)
                scheduleService(context, endCal, false)
            } else {
                scheduleService(context, startCal, true)
                scheduleService(context, endCal, false)
            }
        } else {
            Log.i(TAG, "No shift for $dayName on $date")
        }
    }

    /**
     * scheduleTomorrowFromPrefs(...)
     * WHEN: Called by AlarmReceiver right after START fires.
     * WHAT: Schedules TOMORROW’s start/stop using distinct request codes (1101/1102) so TODAY’s alarms remain.
     */
    fun scheduleTomorrowFromPrefs(context: Context) {
        val user = SharedPref.getInstance(context)?.getUser() ?: return
        val def = user.timetable?.range ?: return
        val multi = user.multipleTimeTables ?: emptyList()
        val tomorrow = LocalDate.now().plusDays(1)

        val activeMulti = multi.find { mt ->
            val s = mt.startDate.toLocalDate(); val e = mt.endDate.toLocalDate()
            tomorrow in s..e
        }
        val dayName = tomorrow.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
        val range = activeMulti?.timetable?.range ?: def
        val shift = range.find { it.day.equals(dayName, ignoreCase = true) } ?: run {
            Log.i(TAG, "No shift for $dayName (tomorrow) from prefs"); return
        }
        if (shift.start == null || shift.end == null) { Log.i(TAG, "Tomorrow OFF"); return }

        val startCal = getCalendarForDate(tomorrow, shift.start, -1)
        val endCal   = getCalendarForDate(tomorrow, shift.end,   1)
        if (endCal.timeInMillis <= startCal.timeInMillis) endCal.add(Calendar.DAY_OF_YEAR, 1)

        cancelTomorrowAlarms(context)
        scheduleServiceWithCode(context, startCal, true,  1101)
        scheduleServiceWithCode(context, endCal,   false, 1102)

        Log.i(TAG, "Re-chained TOMORROW start=${startCal.time} stop=${endCal.time}")
    }

    /**
     * scheduleTodayAndTomorrow(...)
     * WHEN: Use at login/FCM to guarantee both TODAY and TOMORROW are armed.
     * WHAT: Calls scheduleAlarms() for today, then independently schedules tomorrow with 1101/1102.
     */
    fun scheduleTodayAndTomorrow(
        context: Context,
        defaultShifts: List<TimeRange>,
        multipleTimeTables: List<MultipleTimeTable>,
        reschedulePeriodic: Boolean = true
    ) {
        // TODAY (rc=1001/1002; with immediate start if inside window)
        scheduleAlarms(context, defaultShifts, multipleTimeTables, reschedulePeriodic)

        // TOMORROW (rc=1101/1102; never cancels today’s)
        val tomorrow = LocalDate.now().plusDays(1)
        val activeMulti = multipleTimeTables.find { mt ->
            val s = mt.startDate.toLocalDate(); val e = mt.endDate.toLocalDate()
            tomorrow in s..e
        }
        val dayName = tomorrow.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
        val range = activeMulti?.timetable?.range ?: defaultShifts
        val shift = range.find { it.day.equals(dayName, ignoreCase = true) }

        if (shift?.start != null && shift.end != null) {
            val startCal = getCalendarForDate(tomorrow, shift.start, -1)
            val endCal   = getCalendarForDate(tomorrow, shift.end,   1)
            if (endCal.timeInMillis <= startCal.timeInMillis) endCal.add(Calendar.DAY_OF_YEAR, 1)

            cancelTomorrowAlarms(context)
            scheduleServiceWithCode(context, startCal, true,  1101)
            scheduleServiceWithCode(context, endCal,   false, 1102)

            Log.i(TAG, "Scheduled TOMORROW start=${startCal.time} (rc=1101), stop=${endCal.time} (rc=1102)")
        } else {
            Log.i(TAG, "No shift found for $dayName (tomorrow); skipping.")
        }
    }

    // ===== Helpers for "tomorrow" alarms with distinct request codes (1101/1102) =====

    /**
     * scheduleServiceWithCode(...)
     * WHAT: Same as scheduleService but allows custom request code (used for TOMORROW).
     * NOTE: Includes setAlarmClock fallback if exact-alarm permission is missing on S+.
     */
    private fun scheduleServiceWithCode(
        context: Context,
        calendar: Calendar,
        isStart: Boolean,
        requestCode: Int
    ) {
        val intent = Intent(context, MyService::class.java).apply {
            action = if (isStart) "START_SERVICE" else "STOP_SERVICE"
        }
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getService(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            val showIntent = PendingIntent.getActivity(
                context, 0,
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:${context.packageName}")
                },
                PendingIntent.FLAG_IMMUTABLE
            )
            am.setAlarmClock(AlarmManager.AlarmClockInfo(calendar.timeInMillis, showIntent), pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pi)
        }
    }

    /**
     * cancelServiceAlarmByCode(...)
     * WHAT: Cancels a specific PI identified by (action, requestCode). Used to clear TOMORROW (1101/1102).
     */
    private fun cancelServiceAlarmByCode(context: Context, action: String, requestCode: Int) {
        val intent = Intent(context, MyService::class.java).apply { this.action = action }
        val pi = PendingIntent.getService(
            context, requestCode, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pi != null) {
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi)
            Log.i(TAG, "Canceled ($action) alarm rc=$requestCode")
        }
    }

    /**
     * cancelTomorrowAlarms(...)
     * WHAT: Clears TOMORROW’s start (1101) and stop (1102) so we can re-plan cleanly.
     */
    private fun cancelTomorrowAlarms(context: Context) {
        cancelServiceAlarmByCode(context, "START_SERVICE", 1101)
        cancelServiceAlarmByCode(context, "STOP_SERVICE", 1102)
    }
}



