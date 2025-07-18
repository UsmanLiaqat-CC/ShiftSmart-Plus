package com.shiftsmart.plus.periodicAction

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.shiftsmart.plus.services.MyService
import com.shiftsmart.plus.utils.SharedPref
import com.shiftsmart.plus.utils.Utils.isServiceRunning

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
