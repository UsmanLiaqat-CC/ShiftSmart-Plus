package com.shiftsmart.plus.periodicAction

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.shiftsmart.plus.utils.SharedPref

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i("BootReceiver", "Device reboot detected, rescheduling alarms.")

            val user = SharedPref.getInstance(context)?.getUser()
            Log.i("BootReceiver", "startMyService: Retrieved user info = $user")

            if (user != null && user.isActive == true) {
                // Re-fetch shift data and reschedule alarms

                user.timetable?.range?.let {
                    AlarmScheduler.scheduleAlarms(context, it,user.multipleTimeTables!!)
                    Log.i("BootReceiver", "startMyService: Alarms scheduled with range = ${it}")
                }
            }

        }
    }
}
