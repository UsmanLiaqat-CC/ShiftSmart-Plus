package com.shiftsmart.plus.periodicAction

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.ListenableWorker
import com.shiftsmart.plus.models.UserModel
import com.shiftsmart.plus.services.MyService
import com.shiftsmart.plus.utils.SharedPref
import com.shiftsmart.plus.utils.Utils
import com.shiftsmart.plus.utils.Utils.isServiceRunning

class RestartWatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {


        val sharedPref = SharedPref.getInstance(context = context)
        val user = sharedPref?.getUser()

        if (user != null && AlarmReceiver.isInsideShiftWindow(user)) {
            Log.i("BootReceiver", "⏰ Service destroyed during shift - AlarmManager will handle next wake-up")
            // Ensure alarms are scheduled (they should already be, but just in case)
            AlarmReceiver.scheduleNextAlignedAlarm(context)
        } else {
            Log.i("BootReceiver", "⏸️ Service destroyed outside shift - no action needed")
        }


        // reschedule again
        RestartWatchdogManager.scheduleOneMinuteRestart(context)
    }

}
