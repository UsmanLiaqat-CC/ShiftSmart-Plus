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
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.shiftsmart.plus.models.MultipleTimeTable
import com.shiftsmart.plus.models.TimeRange
import com.shiftsmart.plus.services.MyService
import com.shiftsmart.plus.utils.SharedPref
import com.shiftsmart.plus.utils.ShiftUtils.getCalendarForShift
import com.shiftsmart.plus.utils.Utils
import com.shiftsmart.plus.utils.Utils.getCurrentDayName
import com.shiftsmart.plus.utils.Utils.toLocalDate
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Date
import java.util.concurrent.TimeUnit

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
            Log.i(TAG, "========================================")
            Log.i(TAG, "📅 TODAY: $today (${LocalDate.now()})")
            Log.i(TAG, "Today's Shift -> day:${todayShift.day}, start:${todayShift.start}, end:${todayShift.end}")

            // Check if this is an overnight shift
            val startHour = todayShift.start.split(":")[0].toInt()
            val endHour = todayShift.end.split(":")[0].toInt()
            val isOvernightShift = endHour < startHour
            Log.i(TAG, "🌙 Overnight shift: $isOvernightShift (start hour: $startHour, end hour: $endHour)")

            // -1h for START, +1h for STOP
            val startCalendar = getCalendarForShift(todayShift.day, todayShift.start, -1, false)
            val endCalendar   = getCalendarForShift(todayShift.day, todayShift.end, 1, isOvernightShift)

            Log.i(TAG, "⏰ START alarm (shift start - 1h): ${startCalendar?.time}")
            Log.i(TAG, "⏰ STOP alarm  (shift end + 1h):   ${endCalendar?.time}")
            Log.i(TAG, "========================================")

            if (startCalendar != null && endCalendar != null) {
                Log.i(TAG, "🔍 Final calendar times:")
                Log.i(TAG, "   Start millis: ${startCalendar.timeInMillis} (${startCalendar.time})")
                Log.i(TAG, "   End millis:   ${endCalendar.timeInMillis} (${endCalendar.time})")


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
//                    schedulePeriodicAlarm(context)

                    schedulePeriodicWork(context)
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


    fun schedulePeriodicWork(context: Context) {
        val workRequest = PeriodicWorkRequestBuilder<ShiftStatusWorker>(
            15, TimeUnit.MINUTES
        ).setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED) // optional
                .build()
        ).build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "ShiftStatusWork",
            ExistingPeriodicWorkPolicy.UPDATE, // always keep latest
            workRequest
        )

        Log.i(TAG, "⏱ WorkManager periodic shift check scheduled (every 15 min)")
    }


    private fun scheduleService(context: Context, calendar: Calendar, isStart: Boolean) {
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

            val dateFormat = java.text.SimpleDateFormat("EEE MMM dd HH:mm:ss yyyy", java.util.Locale.getDefault())
            Log.i(TAG, "========================================")
            Log.i(TAG, "📝 SCHEDULED ${if (isStart) "START" else "STOP"} SERVICE")
            Log.i(TAG, "   Request Code: $requestCode")
            Log.i(TAG, "   Alarm Time: ${dateFormat.format(calendar.time)}")
            Log.i(TAG, "   Millis: ${calendar.timeInMillis}")
            Log.i(TAG, "   Year: ${calendar.get(Calendar.YEAR)}")
            Log.i(TAG, "   Month: ${calendar.get(Calendar.MONTH) + 1}")
            Log.i(TAG, "   Day: ${calendar.get(Calendar.DAY_OF_MONTH)}")
            Log.i(TAG, "   Hour: ${calendar.get(Calendar.HOUR_OF_DAY)}")
            Log.i(TAG, "   Minute: ${calendar.get(Calendar.MINUTE)}")
            Log.i(TAG, "========================================")

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
//            Log.i("AlarmScheduler", "Wake lock acquired and screen turned on.")
        }
    }

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
     * scheduleTomorrowFromPrefs(...)
     * WHEN: Called by AlarmReceiver right after START fires.
     * WHAT: Schedules TOMORROW’s start/stop using distinct request codes (1101/1102)
     *       so TODAY’s alarms remain intact.
     * WHY: Ensures overnight shifts continue seamlessly (e.g., Mon→Tue, Tue→Wed).
     */
    fun scheduleTomorrowFromPrefs(context: Context) {
        val user = SharedPref.getInstance(context)?.getUser() ?: return
        val defaultShifts = user.timetable?.range ?: return
        val multipleTables = user.multipleTimeTables ?: emptyList()
        val tomorrow = LocalDate.now().plusDays(1)

        // 1️⃣ Pick the correct timetable
        val activeMulti = multipleTables.find { mt ->
            val start = mt.startDate.toLocalDate()
            val end = mt.endDate.toLocalDate()
            tomorrow in start..end
        }

        val dayName = tomorrow.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
        val effectiveRange = activeMulti?.timetable?.range ?: defaultShifts
        val tomorrowShift = effectiveRange.find { it.day.equals(dayName, ignoreCase = true) }

        if (tomorrowShift == null || tomorrowShift.start == null || tomorrowShift.end == null) {
            Log.i(TAG, "scheduleTomorrowFromPrefs → No valid shift for $dayName (OFF)")
            return
        }

        // 2️⃣ Build start and end calendars with ±1h
        val startCal = getCalendarForDate(tomorrow, tomorrowShift.start, -1)
        val endCal = getCalendarForDate(tomorrow, tomorrowShift.end, 1)

        // 🕛 Normalize overnight: if end <= start, push end to next day
        if (endCal.timeInMillis <= startCal.timeInMillis) {
            endCal.add(Calendar.DAY_OF_YEAR, 1)
        }

        // 3️⃣ Cancel any existing tomorrow alarms (rc=1101/1102)
        cancelTomorrowAlarms(context)

        // 4️⃣ Schedule tomorrow’s start and stop
        scheduleServiceWithCode(context, startCal, true, 1101)
        scheduleServiceWithCode(context, endCal, false, 1102)

        Log.i(
            TAG,
            "✅ Tomorrow scheduled: $dayName start=${startCal.time} (rc=1101), stop=${endCal.time} (rc=1102)"
        )
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

        val dateFormat = java.text.SimpleDateFormat("EEE MMM dd HH:mm:ss yyyy", java.util.Locale.getDefault())
        Log.i(TAG, "========================================")
        Log.i(TAG, "📝 SCHEDULED ${if (isStart) "START" else "STOP"} SERVICE (CUSTOM CODE)")
        Log.i(TAG, "   Request Code: $requestCode")
        Log.i(TAG, "   Alarm Time: ${dateFormat.format(calendar.time)}")
        Log.i(TAG, "   Millis: ${calendar.timeInMillis}")
        Log.i(TAG, "   Year: ${calendar.get(Calendar.YEAR)}")
        Log.i(TAG, "   Month: ${calendar.get(Calendar.MONTH) + 1}")
        Log.i(TAG, "   Day: ${calendar.get(Calendar.DAY_OF_MONTH)}")
        Log.i(TAG, "   Hour: ${calendar.get(Calendar.HOUR_OF_DAY)}")
        Log.i(TAG, "   Minute: ${calendar.get(Calendar.MINUTE)}")
        Log.i(TAG, "========================================")

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



