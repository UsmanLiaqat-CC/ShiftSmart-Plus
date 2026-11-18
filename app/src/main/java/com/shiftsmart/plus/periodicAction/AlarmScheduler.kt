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
import com.shiftsmart.plus.models.UserModel
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
 *  - Also provides helpers to schedule TOMORROW’s start/stop using request codes derived from
 *    the target date (see requestCodeForDate) so today’s alarms (1001/1002) aren’t overwritten
 *    and each day's "next day" alarm never collides with any other day's pending alarm.
 *  - Periodic 5-min CALL_API ticker: can be (re)scheduled and is always deduped.
 */


object AlarmScheduler {
    private const val TAG = "AlarmScheduler"

    /**
     * scheduleAlarms(...)
     *
     * WHEN: Call on login, after FCM user/timetable updates, or any time you want to (re)arm today.
     * WHAT: Schedules TODAY's START/STOP for the effective shift using DUAL REDUNDANCY:
     *       1. AlarmManager (primary - exact timing)
     *       2. WorkManager (fallback - survives device restrictions)
     *
     * SHIFT BUFFER: Applies ±1 hour buffer automatically via getCalendarForShift()
     *  - Shift 08:00-18:00 → Service runs 07:00-19:00
     *  - Shift 20:00-02:00 (overnight) → Service runs 19:00-03:00 (next day)
     *  - If NOW is inside window: start service immediately and only schedule STOP.
     *  - Else: schedule both START and STOP.
     *
     * SIDE: Optionally (re)schedules the 5-min CALL_API alarm (one-shot; receiver re-arms).
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
            Log.i(TAG, "Shift configured: ${todayShift.start} - ${todayShift.end}")
            Log.i(TAG, "⏰ ±1 HOUR BUFFER APPLIED:")
            Log.i(TAG, "   • Service will START at: ${todayShift.start} - 1 hour")
            Log.i(TAG, "   • Service will STOP at: ${todayShift.end} + 1 hour")

            // Check if this is an overnight shift
            val startHour = todayShift.start.split(":")[0].toInt()
            val endHour = todayShift.end.split(":")[0].toInt()
            val isOvernightShift = endHour < startHour
            Log.i(TAG, "🌙 Overnight shift: $isOvernightShift (start hour: $startHour, end hour: $endHour)")

            // ✅ getCalendarForShift applies the ±1 hour buffer automatically
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
                    Log.i(TAG, "✅ Inside window → starting service NOW, scheduling STOP at ${endCalendar.time}")
                    startServiceNow(context)
                    scheduleService(context, endCalendar, false)
                } else {
                    // Outside window → schedule both START and STOP
                    Log.i(TAG, "⏭️ Outside window → scheduling START ${startCalendar.time} and STOP ${endCalendar.time}")
                    scheduleService(context, startCalendar, true)
                    scheduleService(context, endCalendar, false)
                }

                // Note: WorkManager health check is now handled by ServiceHealthWorkerManager
                // initialized in MainActivity on login

                // ✅ CRITICAL: Start PeriodicSyncWorker as fallback for Android 10 Doze restrictions
                PeriodicSyncWorkerManager.startPeriodicSync(context)

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
        try {
            val intent = Intent(context, MyService::class.java).apply { action = "START_SERVICE" }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        } catch (e: Exception) {
            // startForegroundService() can throw ForegroundServiceStartNotAllowedException right
            // after boot (app not yet foregrounded/restricted standby bucket). If uncaught here,
            // MyService never gets created, so the 1-minute watchdog it would normally arm from
            // within itself never gets scheduled either — the shift silently never starts until
            // the user opens the app. Arm the watchdog directly so it keeps retrying.
            Log.e(TAG, "❌ startServiceNow() failed - arming retry watchdog", e)
            RestartWatchdogManager.scheduleOneMinuteRestart(context)
        }
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

    /**
     * schedulePeriodicAlarm(...)
     * WHEN: After (re)arming alarms if you want the 5-min CALL_API heartbeat to persist.
     * WHAT: Cancels any existing CALL_API PI and (re)schedules a one-shot exact alarm aligned
     *       with the last sync time (multiple of 5 minutes from last sync).
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

        // Exact-alarm permission gate (S+); you already redirect to settings elsewhere if needed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
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

        // ✅ IMPROVED: Align with last sync time if available
        val triggerTime = getNextAlignedTimeWithLastSync(context)

        // Use exact alarm for precision
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            } else {
                // Fallback if permission not granted
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            }
        } else {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )
        }

        Log.i(TAG, "Scheduled CALL_API alarm at ${Date(triggerTime)}")
    }

    @JvmStatic
    fun cancelComplaintAlarm(context: Context) {
        // Cancel any previously scheduled complaint alarm (request code 5678)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java).apply { action = "COMPLAINT_ALERT" }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 5678, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        SharedPref.getInstance(context)?.clearComplaintAlertTriggerTime()
        Log.i(TAG, "Complaint alarm cancelled (no-op — alarm scheduler removed)")
    }

    /**
     * scheduleService(...)
     * WHEN: Used for TODAY’s start (rc=1001) / stop (rc=1002).
     * WHAT: Schedules exact alarms; NOTE currently acquires a wake lock at *schedule* time,
     *       which is generally not needed — prefer holding a short PARTIAL_WAKE_LOCK when the alarm
     *       actually fires (in receiver/service).
     */
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


            // Use exact alarm for precision
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        calendar.timeInMillis,
                        pendingIntent
                    )
                } else {
                    // Fallback if permission not granted
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        calendar.timeInMillis,
                        pendingIntent
                    )
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    calendar.timeInMillis,
                    pendingIntent
                )
            }

//            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)

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

            // ⛔️ REMOVED: Wake lock at schedule-time is ineffective and wasteful.
            // Wake locks should be acquired when the alarm FIRES (in AlarmReceiver).
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling service", e)
        }
    }



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
     * getNextAlignedTimeWithLastSync(...)
     * WHAT: Returns epoch millis for the next alarm that is aligned with last sync time.
     *       Ensures syncs always happen at multiples of 5 minutes from the last successful sync.
     * WHO: Used by schedulePeriodicAlarm().
     */
    private fun getNextAlignedTimeWithLastSync(context: Context): Long {
        val sharedPref = SharedPref.getInstance(context)
        val lastSyncTimestamp = sharedPref?.getLastSyncTimestamp() ?: 0L

        if (lastSyncTimestamp > 0L) {
            // We have a last sync time - calculate next time that's a multiple of 5 minutes from it
            val currentTime = System.currentTimeMillis()
            val timeSinceLastSync = currentTime - lastSyncTimestamp
            val minutesSinceLastSync = (timeSinceLastSync / (60 * 1000)).toInt()

            // Calculate next multiple of 5 minutes from last sync
            val nextMultipleOf5 = ((minutesSinceLastSync / 5) + 1) * 5
            val nextAlignedTime = lastSyncTimestamp + (nextMultipleOf5 * 60 * 1000L)

            Log.i(TAG, "⏰ Last sync was ${minutesSinceLastSync} minutes ago")
            Log.i(TAG, "⏰ Next sync aligned with last sync: ${Date(nextAlignedTime)} (${nextMultipleOf5} min from last sync)")

            return nextAlignedTime
        } else {
            // No last sync time - use standard 5-minute boundary alignment
            Log.i(TAG, "⏰ No last sync found, using standard 5-minute boundary")
            return getNextFiveMinuteAlignedTime()
        }
    }

    /**
     * findNextShiftDate(...)
     * WHAT: Starting from `from` (inclusive), scans forward up to `maxLookaheadDays` to find the
     *       next date that has a valid shift configured (skipping OFF days).
     * WHY: The alarm chain only advances when a pending alarm fires. If "tomorrow" is an OFF day
     *      and we schedule nothing, no alarm exists to fire and re-arm the day after — the chain
     *      silently dies at the first OFF day, on every Android version, until the app is reopened.
     *      Looking ahead lets us schedule the actual next working day directly, regardless of how
     *      many consecutive OFF days precede it.
     */
    private fun findNextShiftDate(
        from: LocalDate,
        defaultShifts: List<TimeRange>,
        multipleTimeTables: List<MultipleTimeTable>,
        maxLookaheadDays: Int = 14
    ): Pair<LocalDate, TimeRange>? {
        for (offset in 0 until maxLookaheadDays) {
            val candidate = from.plusDays(offset.toLong())
            val activeMulti = multipleTimeTables.find { mt ->
                val start = mt.startDate.toLocalDate()
                val end = mt.endDate.toLocalDate()
                candidate in start..end
            }
            val dayName = candidate.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
            val effectiveRange = activeMulti?.timetable?.range ?: defaultShifts
            val shift = effectiveRange.find { it.day.equals(dayName, ignoreCase = true) }
            if (shift?.start != null && shift.end != null) {
                return candidate to shift
            }
            Log.i(TAG, "findNextShiftDate → $dayName ($candidate) is OFF, checking next day")
        }
        Log.w(TAG, "findNextShiftDate → No shift found within $maxLookaheadDays days from $from")
        return null
    }

    /**
     * requestCodeForDate(...)
     * WHAT: Derives a request code from the actual target date instead of a fixed constant.
     * WHY: A fixed pair (e.g. 1101/1102) reused for "tomorrow" every single day caused a bug:
     *      when that day's own START fired, it re-armed "the next tomorrow" using the SAME pair,
     *      cancelling that same day's still-pending STOP before it ever fired. Deriving the code
     *      from the date means today's and tomorrow's alarms are NEVER the same PendingIntent
     *      identity, so rescheduling tomorrow can never cancel today's pending alarm.
     *      Range (3000+) is kept disjoint from the legacy fixed codes (1001/1002/1101/1102) so
     *      already-scheduled legacy alarms on existing installs can coexist harmlessly until they
     *      fire and this logic takes over.
     */
    private fun requestCodeForDate(date: LocalDate, isStart: Boolean): Int {
        val base = 3000 + (date.toEpochDay() % 1000).toInt() * 2
        return if (isStart) base else base + 1
    }

    /**
     * cancelLegacyFixedTomorrowAlarms(...)
     * WHAT: One-time cleanup of the OLD fixed rc=1101/1102 scheme on already-deployed installs.
     * WHY: Ensures existing devices self-heal onto the new date-derived scheme without needing
     *      a fresh app open — the next natural reschedule call cleans up any stale legacy alarm.
     */
    private fun cancelLegacyFixedTomorrowAlarms(context: Context) {
        cancelServiceAlarmByCode(context, "START_SERVICE", 1101)
        cancelServiceAlarmByCode(context, "STOP_SERVICE", 1102)
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
     * scheduleTomorrowFromPrefs(...)
     * WHEN: Called by AlarmReceiver right after START fires.
     * WHAT: Schedules TOMORROW’s start/stop using a request code derived from tomorrow's date
     *       so TODAY’s alarms remain intact.
     * WHY: Ensures overnight shifts continue seamlessly (e.g., Mon→Tue, Tue→Wed).
     */
    fun scheduleTomorrowFromPrefs(context: Context) {
        val user = SharedPref.getInstance(context)?.getUser() ?: return
        val defaultShifts = user.timetable?.range ?: return
        val multipleTables = user.multipleTimeTables ?: emptyList()

        // Look ahead (skipping OFF days) instead of only checking literal "tomorrow" — otherwise
        // a single OFF day in the schedule leaves nothing pending to fire and re-arm the chain.
        val next = findNextShiftDate(LocalDate.now().plusDays(1), defaultShifts, multipleTables)
        if (next == null) {
            Log.i(TAG, "scheduleTomorrowFromPrefs → No upcoming shift found in lookahead window")
            return
        }
        val (nextDate, nextShift) = next
        val dayName = nextDate.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }

        // 2️⃣ Build start and end calendars with ±1h
        val startCal = getCalendarForDate(nextDate, nextShift.start!!, -1)
        val endCal = getCalendarForDate(nextDate, nextShift.end!!, 1)

        // 🕛 Normalize overnight: if end <= start, push end to next day
        if (endCal.timeInMillis <= startCal.timeInMillis) {
            endCal.add(Calendar.DAY_OF_YEAR, 1)
        }

        // 3️⃣ Cancel any existing alarm for the next shift date (date-derived code) + legacy fixed codes
        cancelTomorrowAlarms(context, nextDate)

        // 4️⃣ Schedule the next shift's start and stop using codes derived from that date
        val startCode = requestCodeForDate(nextDate, true)
        val stopCode = requestCodeForDate(nextDate, false)
        scheduleServiceWithCode(context, startCal, true, startCode)
        scheduleServiceWithCode(context, endCal, false, stopCode)

        Log.i(
            TAG,
            "✅ Next shift scheduled: $dayName ($nextDate) start=${startCal.time} (rc=$startCode), stop=${endCal.time} (rc=$stopCode)"
        )
    }


    /**
     * scheduleTodayAndTomorrow(...)
     * WHEN: Use at login/FCM to guarantee both TODAY and TOMORROW are armed.
     * WHAT: Calls scheduleAlarms() for today, then independently schedules tomorrow with a
     *       date-derived request code.
     */
    fun scheduleTodayAndTomorrow(
        context: Context,
        defaultShifts: List<TimeRange>,
        multipleTimeTables: List<MultipleTimeTable>,
        reschedulePeriodic: Boolean = true
    ) {
        // TODAY (rc=1001/1002; with immediate start if inside window)
        scheduleAlarms(context, defaultShifts, multipleTimeTables, reschedulePeriodic)

        // NEXT SHIFT (date-derived rc; never cancels today’s). Looks ahead past OFF days so the
        // chain has something pending to fire even if tomorrow (or several days) are OFF.
        val next = findNextShiftDate(LocalDate.now().plusDays(1), defaultShifts, multipleTimeTables)
        if (next != null) {
            val (nextDate, shift) = next
            val startCal = getCalendarForDate(nextDate, shift.start!!, -1)
            val endCal   = getCalendarForDate(nextDate, shift.end!!,   1)
            if (endCal.timeInMillis <= startCal.timeInMillis) endCal.add(Calendar.DAY_OF_YEAR, 1)

            cancelTomorrowAlarms(context, nextDate)
            val startCode = requestCodeForDate(nextDate, true)
            val stopCode = requestCodeForDate(nextDate, false)
            scheduleServiceWithCode(context, startCal, true,  startCode)
            scheduleServiceWithCode(context, endCal,   false, stopCode)

            Log.i(TAG, "Scheduled NEXT SHIFT $nextDate start=${startCal.time} (rc=$startCode), stop=${endCal.time} (rc=$stopCode)")
        } else {
            Log.i(TAG, "No upcoming shift found in lookahead window; skipping.")
        }
    }

    // ===== Helpers for "tomorrow" alarms with date-derived request codes =====

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



        // Use exact alarm for precision
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    calendar.timeInMillis,
                    pi
                )
            } else {
                // Fallback if permission not granted
                am.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    calendar.timeInMillis,
                    pi
                )
            }
        } else {
            am.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                calendar.timeInMillis,
                pi
            )
        }

    }

    /**
     * cancelServiceAlarmByCode(...)
     * WHAT: Cancels a specific PI identified by (action, requestCode). Used to clear TOMORROW's alarm.
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
     * WHAT: Clears the pending alarm for the given target date (start+stop, date-derived codes)
     *       so we can re-plan cleanly. Also purges the legacy fixed rc=1101/1102 scheme so
     *       already-deployed devices migrate onto the date-derived scheme automatically.
     */
    private fun cancelTomorrowAlarms(context: Context, targetDate: LocalDate) {
        cancelServiceAlarmByCode(context, "START_SERVICE", requestCodeForDate(targetDate, true))
        cancelServiceAlarmByCode(context, "STOP_SERVICE", requestCodeForDate(targetDate, false))
        cancelLegacyFixedTomorrowAlarms(context)
    }

    /**
     * Cancel all service and periodic alarms.
     * Use on local logout/session expiry cleanup.
     */
    fun cancelAllAlarms(context: Context) {
        cancelServiceAlarm(context, true)   // today start (1001)
        cancelServiceAlarm(context, false)  // today stop (1002)
        // Cancel both today's and tomorrow's date-derived "next day" alarms (whichever is
        // actually pending depends on when this runs relative to the last schedule call),
        // plus the legacy fixed rc=1101/1102 scheme.
        cancelTomorrowAlarms(context, LocalDate.now())
        cancelTomorrowAlarms(context, LocalDate.now().plusDays(1))

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val periodicIntent = Intent(context, AlarmReceiver::class.java).apply { action = "CALL_API" }
        val periodicPi = PendingIntent.getBroadcast(
            context,
            1234,
            periodicIntent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        periodicPi?.let { alarmManager.cancel(it) }

        Log.i(TAG, "✅ Cancelled all scheduled alarms")
    }
}
