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
import com.shiftsmart.plus.utils.SharedPref
import com.shiftsmart.plus.utils.Utils

import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Date

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

                "CALL_API" -> {
                    Log.i("AlarmReceiver", "Received CALL_API at ${Utils.getCurrentDateTime()}")

                    val user = SharedPref.getInstance(context)?.getUser()
                    if (user == null) {
                        Log.w("AlarmReceiver", "No user found; skipping CALL_API")
                        return
                    }

                    try {
                        val db = ShiftSmartPlusDatabase.getInstance(context)
                        val dao = db.dbDao()
                        val latestDefaultRecord = dao.getLatestDefaultRecord(user._id.toString())

                        val currentTimeStr = Utils.getCurrent24HourTime()
                        val formatter = DateTimeFormatter.ofPattern("HH:mm")

                        val now = LocalTime.parse(currentTimeStr, formatter)
                        val lastRecordTime = latestDefaultRecord?.localTime?.let {
                            try { LocalTime.parse(it, formatter) } catch (e: Exception) { null }
                        }

                        val minutesDiff = lastRecordTime?.let {
                            Duration.between(it, now).toMinutes()
                        } ?: Long.MAX_VALUE

                        if (minutesDiff >= 5) {
                            Log.i("AlarmReceiver", "⏱ Time diff = $minutesDiff min → Executing API call")

                            val apiIntent = Intent(context, MyService::class.java).apply {
                                action = MyService.ACTION_CALL_API
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                                context.startForegroundService(apiIntent)
                            else
                                context.startService(apiIntent)

                        } else {
                            Log.i("AlarmReceiver", "⏸ Time diff = $minutesDiff min (<5) → Skipping API call")
                        }
                        scheduleNextAlignedAlarm(context)
                    } catch (e: Exception) {
                        Log.e("AlarmReceiver", "Error in CALL_API logic", e)
                    }
                }
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

            val now = Calendar.getInstance().timeInMillis
            val nextAligned = ((now / (5 * 60 * 1000)) + 1) * (5 * 60 * 1000)

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
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(nextAligned, showIntent),
                    pendingIntent
                )
                Log.w("AlarmReceiver", "CALL_API scheduled via setAlarmClock at: ${Date(nextAligned)}")
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextAligned, pendingIntent)
                Log.d("AlarmReceiver", "Next aligned alarm scheduled at: ${Date(nextAligned)}")
            }
        }
    }
}
