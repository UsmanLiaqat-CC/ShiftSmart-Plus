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
            if (!isServiceRunning(context, MyService::class.java)) {
                // ⚠️ This is the actual point of the watchdog: MyService may have failed to
                // start (e.g. startForeground() threw ForegroundServiceStartNotAllowedException
                // right after boot) or been killed by the OS. Relaunch it here instead of just
                // rescheduling the next CALL_API alarm, otherwise a dead service never comes back.
                Log.i("RestartWatchdogReceiver", "🔁 Service not running during shift - restarting MyService")
                val serviceIntent = Intent(context, MyService::class.java).apply {
                    action = MyService.ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } else {
                Log.i("RestartWatchdogReceiver", "✅ Service already running during shift")
            }
            // Ensure alarms are scheduled (they should already be, but just in case)
            AlarmReceiver.scheduleNextAlignedAlarm(context)
        } else {
            Log.i("RestartWatchdogReceiver", "⏸️ Outside shift - no action needed")
        }


        // reschedule again
        RestartWatchdogManager.scheduleOneMinuteRestart(context)
    }

}
