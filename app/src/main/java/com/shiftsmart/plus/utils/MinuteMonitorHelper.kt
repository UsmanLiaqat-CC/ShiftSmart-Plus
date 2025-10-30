package com.shiftsmart.plus.utils

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.shiftsmart.plus.periodicAction.MinuteChangeReceiver

object MinuteMonitorHelper {
    
    private val TAG = "MinuteMonitorHelper"
    private var minuteReceiver: MinuteChangeReceiver? = null
    private var isRegistered = false

    fun startMonitoring(context: Context) {
        if (isRegistered) {
            Log.d(TAG, "⚠️ Already monitoring")
            return
        }

        try {
            minuteReceiver = MinuteChangeReceiver()
            val filter = IntentFilter(Intent.ACTION_TIME_TICK)
            context.applicationContext.registerReceiver(minuteReceiver, filter)
            isRegistered = true
            Log.i(TAG, "✅ Minute monitoring started")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start monitoring", e)
        }
    }

    fun stopMonitoring(context: Context) {
        if (!isRegistered || minuteReceiver == null) {
            Log.d(TAG, "⚠️ Not monitoring")
            return
        }

        try {
            context.applicationContext.unregisterReceiver(minuteReceiver)
            minuteReceiver = null
            isRegistered = false
            Log.i(TAG, "🛑 Minute monitoring stopped")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to stop monitoring", e)
        }
    }

    fun isMonitoring(): Boolean = isRegistered
}
