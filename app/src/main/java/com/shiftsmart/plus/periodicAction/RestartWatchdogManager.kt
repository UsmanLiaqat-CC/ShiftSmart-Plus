package com.shiftsmart.plus.periodicAction

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

object RestartWatchdogManager {

    @SuppressLint("ScheduleExactAlarm")
    fun scheduleOneMinuteRestart(context: Context) {

        val intent = Intent(context, RestartWatchdogReceiver::class.java)

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            2001,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val alarmManager =
            context.getSystemService(Context.ALARM_SERVICE) as AlarmManager


        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        System.currentTimeMillis() + 60_000,
                        pendingIntent
                    )
                    Log.i("RestartWatchdogManager", "✅ Exact alarm scheduled")
                } else {
                    Log.e("RestartWatchdogManager", "❌ Cannot schedule exact alarms - using fallback")
                    // Fallback to inexact alarm
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        System.currentTimeMillis() + 60_000,
                        pendingIntent
                    )
                }
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 60_000,
                    pendingIntent
                )
                Log.i("RestartWatchdogManager", "✅ Exact alarm scheduled")
            }
            else -> {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 60_000,
                    pendingIntent
                )
                Log.i("RestartWatchdogManager", "✅ Exact alarm scheduled")
            }
        }

    }

    fun cancelOneMinuteRestart(context: Context) {
        val intent = Intent(context, RestartWatchdogReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            2001,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )

        if (pendingIntent != null) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(pendingIntent)
            Log.i("RestartWatchdogManager", "✅ Restart watchdog cancelled")
        }
    }
}
