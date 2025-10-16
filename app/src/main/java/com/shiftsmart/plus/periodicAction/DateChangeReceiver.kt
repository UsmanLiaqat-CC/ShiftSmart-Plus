package com.shiftsmart.plus.periodicAction

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

import com.shiftsmart.plus.services.MyService
import com.shiftsmart.plus.utils.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DateChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return
        Log.i("DateChangeReceiver", "🕛 Date changed → ${Utils.getCurrentDateTime()}")

//        try {
//            // 1️⃣ Re-schedule tomorrow’s alarms (next day shifts)
//            AlarmScheduler.scheduleTomorrowFromPrefs(context)
//
//            // 2️⃣ Optionally restart service if still within active buffer
//            val serviceIntent = Intent(context, MyService::class.java).apply {
//                action = MyService.ACTION_CALL_API   // triggers keep-alive check
//            }
//
//            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
//                context.startForegroundService(serviceIntent)
//            else
//                context.startService(serviceIntent)
//
////            // 3️⃣ Force a backup sync right at midnight
////            CoroutineScope(Dispatchers.IO).launch {
////                try {
////                    val app = context.applicationContext
////                    val attendanceSyncManager = app?.attendanceSyncManager
////                    attendanceSyncManager?.startSyncProcess()
////                    Log.i("DateChangeReceiver", "✅ Midnight sync executed.")
////                } catch (e: Exception) {
////                    Log.e("DateChangeReceiver", "Error executing midnight sync", e)
////                }
////            }
//
//        } catch (e: Exception) {
//            Log.e("DateChangeReceiver", "Error in onReceive", e)
//        }
    }
}
