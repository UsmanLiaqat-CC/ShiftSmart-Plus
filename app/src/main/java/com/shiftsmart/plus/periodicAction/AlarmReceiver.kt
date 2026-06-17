package com.shiftsmart.plus.periodicAction

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.shiftsmart.plus.models.UserModel
import com.shiftsmart.plus.services.MyService
import com.shiftsmart.plus.ui.activities.WakeUpActivity
import com.shiftsmart.plus.utils.SharedPref
import com.shiftsmart.plus.utils.ShiftUtils
import com.shiftsmart.plus.utils.Utils
import com.shiftsmart.plus.utils.Utils.toLocalDate
import com.shiftsmart.plus.utils.DeviceCompatibilityHelper
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Calendar
import java.util.Date
import java.util.Locale

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
        // ✅ CRITICAL: Acquire wake lock IMMEDIATELY when alarm fires
        // Use longer wake lock for problematic devices (Mara/Mobicel)
        val wakeLockDuration = if (DeviceCompatibilityHelper.needsAggressiveWakeLock()) {
            5 * 60 * 1000L // 5 minutes for problematic devices
        } else {
            3 * 60 * 1000L // 3 minutes for normal devices
        }

        val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).run {
            newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ShiftSmart::AlarmReceiverWakeLock").apply {
                setReferenceCounted(false)
                acquire(wakeLockDuration)
            }
        }

        // Log device info on first alarm
        if (intent?.action == "START_SERVICE") {
            DeviceCompatibilityHelper.logDeviceInfo()
        }

        try {
            Log.i("TAG", "AlarmReceiver: onReceive at ${Utils.getCurrentDateTime()}")

            when (intent?.action) {

                "START_SERVICE" -> {
                    Log.i(TAG, "Received START_SERVICE_ALARM")

                    // ✅ Check if we have required permissions before starting foreground service
                    if (!hasRequiredPermissions(context)) {
                        Log.e(TAG, "❌ Cannot start foreground service - missing required permissions")
                        Log.e(TAG, "   User needs to grant location permissions from app settings")
                        // Schedule next attempt in case user grants permissions later
                        AlarmScheduler.scheduleTomorrowFromPrefs(context)
                        return
                    }

                    // Check if we're inside shift before starting
                    val user = SharedPref.getInstance(context)?.getUser()
                    if (user != null && isInsideShiftWindow(user)) {
                        Log.i(TAG, "✅ Inside shift - starting service")

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
                        Log.i(TAG, "⏭️ Outside shift window - skipping service start")
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
                    Log.i(TAG, "🔔 Received CALL_API at ${Utils.getCurrentDateTime()}")

                    val user = SharedPref.getInstance(context)?.getUser()
                    if (user == null) {
                        Log.w(TAG, "❌ No user found; skipping CALL_API")
                        return
                    }

                    // ✅ Check if we have required permissions before starting foreground service
                    if (!hasRequiredPermissions(context)) {
                        Log.e(TAG, "❌ Cannot start foreground service - missing required permissions")
                        Log.e(TAG, "   User needs to grant location permissions from app settings")
                        // Schedule next attempt in case user grants permissions later
                        scheduleNextAlarmFromCurrentTime(context)
                        return
                    }

                    // ✅ Check if we're inside shift window before processing
                    if (!isInsideShiftWindow(user)) {
                        Log.i(TAG, "⏭️ Outside shift window - skipping CALL_API")
                        // Stop service if running
                        val stopIntent = Intent(context, MyService::class.java).apply {
                            action = MyService.ACTION_STOP
                        }
                        context.startService(stopIntent)
                        // Schedule next alarm from current time, not last sync time
                        scheduleNextAlarmFromCurrentTime(context)

                        return
                    }

                    Log.i(TAG, "✅ Inside shift window - processing CALL_API")

                    // ✅ NEW APPROACH: Always start service with ACTION_COLLECT_AND_STOP
                    // This tells service to: collect data → save → sync → stop itself
                    Log.i(TAG, "🔄 Starting service with COLLECT_AND_STOP action")
                    val collectIntent = Intent(context, MyService::class.java).apply {
                        action = MyService.ACTION_COLLECT_AND_STOP
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(collectIntent)
                    } else {
                        context.startService(collectIntent)
                    }

                    // ✅ Service will handle:
                    // 1. 5-minute boundary validation
                    // 2. Data collection and saving
                    // 3. API sync
                    // 4. Scheduling next alarm
                    // 5. Stopping itself
                    Log.i(TAG, "✅ Service will collect data and auto-stop after completion")
                }

            }

        } catch (e: Exception) {
            Log.e("TAG", "Error in AlarmReceiver onReceive", e)
        } finally {
            // ✅ CRITICAL: Always release wake lock
            if (wakeLock.isHeld) {
                wakeLock.release()
                Log.d("AlarmReceiver", "Wake lock released")
            }
        }
    }

    companion object {
        private const val TAG = "AlarmReceiver"

        /**
         * Check if all required permissions for foreground service with location are granted
         */
        private fun hasRequiredPermissions(context: Context): Boolean {
            val fineLocation = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            val coarseLocation = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            // For Android 14+ (API 34+), also need FOREGROUND_SERVICE_LOCATION permission
            val foregroundServiceLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.FOREGROUND_SERVICE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true // Not required on older versions
            }

            val hasPermissions = fineLocation && coarseLocation && foregroundServiceLocation

            if (!hasPermissions) {
                Log.e(TAG, "❌ Missing required permissions for foreground service:")
                Log.e(TAG, "   - ACCESS_FINE_LOCATION: $fineLocation")
                Log.e(TAG, "   - ACCESS_COARSE_LOCATION: $coarseLocation")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    Log.e(TAG, "   - FOREGROUND_SERVICE_LOCATION: $foregroundServiceLocation")
                }
            }

            return hasPermissions
        }

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
         * scheduleNextAlarmFromCurrentTime(...)
         * WHEN: When outside shift window or need to schedule from current time
         * WHAT: Cancels existing CALL_API PI and schedules next 5-min boundary from NOW
         */
        @JvmStatic
        fun scheduleNextAlarmFromCurrentTime(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val now = System.currentTimeMillis()
            val nextAligned = ((now / (5 * 60 * 1000)) + 1) * (5 * 60 * 1000)

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
            Log.i("AlarmReceiver", "⏰ Next CALL_API alarm scheduled from current time at: $timeStr (${Date(nextAligned)})")
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


    }
}
