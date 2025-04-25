package com.shiftsmart.plus.utils
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Configuration
import com.shiftsmart.plus.R

import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

import androidx.work.WorkManager
import com.shiftsmart.plus.periodicAction.KeepAliveWorker
import com.shiftsmart.plus.services.MyService


@HiltAndroidApp
class MyApp : Application(), Configuration.Provider {

    override fun onCreate() {
        super.onCreate()

//        // Create notification channels
//        createChannel(
//            applicationContext,
//            getString(R.string.breakfast_notification_channel_id),
//            getString(R.string.breakfast_notification_channel_name)
//        )

        // Initialize WorkManager
        WorkManager.initialize(this, workManagerConfiguration)

//        checkAndStartService(context = this)
    }
    // Call this when your app starts
    fun checkAndStartService(context: Context) {
        // For Huawei devices
        if (Build.MANUFACTURER.equals("huawei", ignoreCase = true)) {
            try {
                val intent = Intent()
                intent.component = ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity"
                )
                context.startActivity(intent)
            } catch (e: Exception) {
                // Fallback
            }
        }

        // Start keep-alive worker
        KeepAliveWorker.schedule(context)

        // Start the service
        val serviceIntent = Intent(context, MyService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
    private fun createChannel(context: Context, channelId: String, channelName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(channelId, channelName, importance)
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }


    override val workManagerConfiguration: Configuration
        get() =
             Configuration.Builder()
                .setMinimumLoggingLevel(android.util.Log.INFO) // Optional: Set minimum logging level for WorkManager
                .build()

}
