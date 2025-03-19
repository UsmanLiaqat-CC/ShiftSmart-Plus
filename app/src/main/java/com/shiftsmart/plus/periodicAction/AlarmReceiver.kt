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

            val serviceIntent = Intent(it, MyService::class.java)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent) // 🔹 Use startForegroundService for Android 8+
            } else {
                context.startService(serviceIntent) // 🔹 For lower versions
            }

        }
    }

}
