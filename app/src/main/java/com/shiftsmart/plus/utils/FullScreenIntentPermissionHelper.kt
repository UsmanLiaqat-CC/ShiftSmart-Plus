package com.shiftsmart.plus.utils

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

object FullScreenIntentPermissionHelper {

    private const val ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT =
        "android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT"

    fun isRuntimePermissionCheckSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    }

    fun canUseFullScreenIntent(context: Context): Boolean {
        if (!isRuntimePermissionCheckSupported()) return true
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return notificationManager.canUseFullScreenIntent()
    }

    fun buildManageFullScreenIntentSettingsIntent(context: Context): Intent {
        return Intent(ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun buildAppDetailsSettingsIntent(context: Context): Intent {
        return Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
