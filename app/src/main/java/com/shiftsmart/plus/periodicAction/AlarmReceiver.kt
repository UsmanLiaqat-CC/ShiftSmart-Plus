package com.shiftsmart.plus.periodicAction

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.shiftsmart.plus.services.MyService
import com.shiftsmart.plus.utils.SharedPref
import com.shiftsmart.plus.utils.Utils
import com.shiftsmart.plus.utils.Utils.isServiceRunning

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        context?.let {
            Log.i("AlarmReceiver", "onReceive: ")
          /*  val user= SharedPref.getInstance(context = it)?.getUser()
            user?.let {user1->
                Log.i("AlarmReceiver", "onReceive: user1:${user1}")
                if (user1?.isActive == true) {
                    // use here workmanager
                    // 🔹 Schedule WorkManager for periodic API calls
                    Log.d("AlarmReceiver", "Alarm Triggered, API Call Started")

                    val serviceIntent = Intent(it, MyService::class.java)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent) // 🔹 Use startForegroundService for Android 8+
                    } else {
                        context.startService(serviceIntent) // 🔹 For lower versions
                    }

                    // Check if the service is running
                    if (!isServiceRunning(it, MyService::class.java)) {
                        val serviceIntent = Intent(it, MyService::class.java)

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent) // 🔹 Use startForegroundService for Android 8+
                        } else {
                            context.startService(serviceIntent) // 🔹 For lower versions
                        }


                        Log.i("AlarmReceiver", "Service is not running. Starting it now. ${Utils.getCurrentDateTime()}")
                        ContextCompat.startForegroundService(it, serviceIntent)
                    }
                    else {
                        Log.i("AlarmReceiver", "Service is already running. Sending update. ${Utils.getCurrentDateTime()}")
                        // Send a broadcast to update the service without restarting it
                        val updateIntent = Intent("UPDATE_NOTIFICATION")
                        updateIntent.putExtra("message", "Triggering API call via WorkManager")
                        LocalBroadcastManager.getInstance(it).sendBroadcast(updateIntent)

                        // Alternatively, if you want to directly update the service, use the custom action
                        val serviceIntent = Intent(it, MyService::class.java)
                        serviceIntent.putExtra("action", "update") // Custom action to trigger update
                        it.startService(serviceIntent) // Use startService instead of startForegroundService
                    }
                }
            }*/

            val serviceIntent = Intent(it, MyService::class.java)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent) // 🔹 Use startForegroundService for Android 8+
            } else {
                context.startService(serviceIntent) // 🔹 For lower versions
            }



        }
    }

}
