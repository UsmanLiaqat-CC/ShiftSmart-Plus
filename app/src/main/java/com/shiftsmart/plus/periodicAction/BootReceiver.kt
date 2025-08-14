package com.shiftsmart.plus.periodicAction

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import android.util.Log
import com.shiftsmart.plus.services.MyService
import com.shiftsmart.plus.utils.SharedPref
import com.shiftsmart.plus.utils.Utils.isServiceRunning

/*
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i("BootReceiver", "Device reboot detected, rescheduling alarms.")

            val user = SharedPref.getInstance(context)?.getUser()
            Log.i("BootReceiver", "startMyService: Retrieved user info = $user")

            if (user != null && user.isActive) {
                // Re-fetch shift data and reschedule alarms

                user.timetable?.range?.let {
                    AlarmScheduler.scheduleAlarms(context, it,user.multipleTimeTables!!)
                    Log.i("BootReceiver", "startMyService: Alarms scheduled with range = ${it}")
                }
            }else{
                if (isServiceRunning(context, MyService::class.java)) {
                    Log.i("BootReceiver", "Service is running. Stopping service.")

                    val notificationManager =context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    notificationManager.cancelAll()
                    Log.i("BootReceiver", "Service is running. Stopping it now.")
//                    context.stopService(Intent(context, MyService::class.java))
                    val stopIntent = Intent(context, MyService::class.java).apply {
                        action = MyService.ACTION_STOP
                    }
                    context.startService(stopIntent)
                } else {
                    Log.i("BootReceiver", "Service is not running. No action needed.")
                }
            }

        }
    }
}
*/
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        var action = intent.action ?: return
        Log.i("BootReceiver", "onReceive: $action → reschedule if needed")

        // We’ll act on reboot and clock changes
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_TIME_CHANGED &&
            action != Intent.ACTION_TIMEZONE_CHANGED) {
            return
        }

        val appContext = context.applicationContext

        // ⚠️ Before user unlock, credential-protected storage (your SharedPref) may be unavailable.
        //    If you don't use device-protected storage, wait for unlock.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val um = appContext.getSystemService(UserManager::class.java)
            if (um != null && !um.isUserUnlocked) {
                Log.w("BootReceiver", "User not unlocked yet. Deferring reschedule.")
                return
            }
        }

        try {
            val user = SharedPref.getInstance(appContext)?.getUser()
            Log.i("BootReceiver", "Retrieved user from SharedPref: $user")

            if (user?.isActive == true) {
                // ✅ Active user → re-arm TODAY & TOMORROW.
                // This method will start immediately if we are inside the window,
                // and it also re-arms the 5-min CALL_API ticker.
                val def = user.timetable?.range ?: emptyList()
                val multi = user.multipleTimeTables ?: emptyList()

                if (def.isEmpty() && multi.isEmpty()) {
                    Log.w("BootReceiver", "No timetable data to schedule; skipping.")
                    return
                }

                AlarmScheduler.scheduleTodayAndTomorrow(
                    context = appContext,
                    defaultShifts = def,
                    multipleTimeTables = multi,
                    reschedulePeriodic = true
                )
                Log.i("BootReceiver", "Alarms (today+tomorrow) scheduled after $action")
            } else {
                // 🚫 Inactive user → ensure service is stopped (if somehow running)
                if (isServiceRunning(appContext, MyService::class.java)) {
                    Log.i("BootReceiver", "User inactive; stopping running service.")
                    val notificationManager =
                        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    notificationManager.cancelAll()

                    val stopIntent = Intent(appContext, MyService::class.java).apply {
                        action = MyService.ACTION_STOP
                    }
                    // Starting a service to issue STOP action is fine here
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        appContext.startForegroundService(stopIntent)
                    } else {
                        appContext.startService(stopIntent)
                    }
                } else {
                    Log.i("BootReceiver", "User inactive; service already not running.")
                }
            }
        } catch (e: Exception) {
            Log.e("BootReceiver", "Error during boot/time reschedule", e)
        }
    }

    // Simple running check (best-effort). Consider keeping a sticky flag/notification if you need stronger guarantees.
    private fun isServiceRunning(context: Context, clazz: Class<*>): Boolean {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.getRunningServices(Int.MAX_VALUE).any { it.service.className == clazz.name }
        } catch (e: Exception) {
            Log.e("BootReceiver", "isServiceRunning error", e)
            false
        }
    }
}
