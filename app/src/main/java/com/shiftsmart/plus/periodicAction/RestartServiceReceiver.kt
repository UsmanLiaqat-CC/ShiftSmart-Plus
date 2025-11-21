package com.shiftsmart.plus.periodicAction

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.shiftsmart.plus.models.UserModel
import com.shiftsmart.plus.services.MyService
import com.shiftsmart.plus.utils.SharedPref
import com.shiftsmart.plus.utils.Utils.isServiceRunning

class RestartServiceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {

        val sharedPref = SharedPref.getInstance(context = context)
        val user = sharedPref?.getUser()

        if (user != null && AlarmReceiver.isInsideShiftWindow(user)) {
            Log.w("TAG", "🚨 Service destroyed during shift - scheduling emergency restart in 1 minute")
            handleUserFromKillService(context,user)
        }else{
            Log.e("RestartServiceReceiver", "user null")
        }
    }

    fun handleUserFromKillService(context: Context, user: UserModel) {
        try {

            if (user.isActive) {
                Log.i("MyFirebaseMessagingService", "User is active. Scheduling alarms.")

                val timetable = user.timetable?.range
                val multiTimeTables = user.multipleTimeTables

                AlarmScheduler.scheduleAlarms(
                    context = context,
                    defaultShifts = timetable!!,
                    multipleTimeTables = multiTimeTables!!
                )

            } else {
                Log.w("MyFirebaseMessagingService", "User is not active. Skipping alarm scheduling.")

                if (isServiceRunning(context, MyService::class.java)) {
                    Log.i("MyFirebaseMessagingService", "Service is running. Stopping service.")

                    val notificationManager =context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    notificationManager.cancelAll()
                    Log.i("Service", "Service is running. Stopping it now.")
//                    context.stopService(Intent(context, MyService::class.java))
                    val stopIntent = Intent(context, MyService::class.java).apply {
                        action = MyService.ACTION_STOP
                    }
                    context.startService(stopIntent)
                } else {
                    Log.i("MyFirebaseMessagingService", "Service is not running. No action needed.")
                }
            }

        } catch (e: Exception) {
            Log.e("MyFirebaseMessagingService", "Error handling user from FCM", e)
        }
    }

}
