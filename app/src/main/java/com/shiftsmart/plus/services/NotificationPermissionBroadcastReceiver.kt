package com.shiftsmart.plus.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.shiftsmart.plus.utils.Utils

class NotificationPermissionBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Log.i(TAG, "Received ${intent?.action}")

        if (Utils.isNotificationPermissionGranted(context)) return
        if (!NotificationPermissionGuardService.isServiceEnabled(context)) return

        try {
            context.startActivity(NotificationPermissionGuardService.buildNotificationSettingsIntent(context))
        } catch (e: Exception) {
            Log.e(TAG, "Unable to open notification settings from receiver", e)
        }
    }

    companion object {
        private const val TAG = "NotificationReceiver"
    }
}
