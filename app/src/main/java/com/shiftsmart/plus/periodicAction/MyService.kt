package com.shiftsmart.plus.periodicAction

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.shiftsmart.plus.R
import com.shiftsmart.plus.ui.activities.MainActivity
import java.util.concurrent.TimeUnit

class MyService : Service() {
    private var notificationManager: NotificationManager? = null
    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra("message") ?: return
            updateNotification(message)
        }
    }
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        notificationManager = getSystemService(NotificationManager::class.java)

        val filter = IntentFilter("UPDATE_NOTIFICATION")
        LocalBroadcastManager.getInstance(this).registerReceiver(notificationReceiver, filter)
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        startForeground(1, createNotification("API calls in progress..."))
//        startApiWorker()
        return START_STICKY
    }

    private fun createNotificationChannel() {
        Log.i("MyService", "createNotificationChannel: ")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                getString(R.string.breakfast_notification_channel_id),
                getString(R.string.breakfast_notification_channel_name),
                NotificationManager.IMPORTANCE_UNSPECIFIED
            )
            if (notificationManager != null) {
                notificationManager!!.createNotificationChannel(serviceChannel)
            }
        }
    }

    private fun createNotification(message: String): Notification {
        Log.i("MyService", "createNotification: ")

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE // ✅ Fix PendingIntent
        )
        return NotificationCompat.Builder(this, getString(R.string.breakfast_notification_channel_id))
            .setContentTitle("Service Running")
            .setContentText(message)
            .setSmallIcon(R.mipmap.ic_launcher)// ✅ Use valid icon
            .setContentIntent(pendingIntent)
            .build()
    }

    fun updateNotification(message: String) {
        Log.i("MyService", "updateNotification: ")
        if (notificationManager != null) {
            notificationManager!!.notify(1, createNotification(message))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i("MyService", "onDestroy: ")

        LocalBroadcastManager.getInstance(this).unregisterReceiver(notificationReceiver)
        WorkManager.getInstance(this).cancelAllWorkByTag(WORK_TAG)
        AlarmScheduler.cancelAlarms(this)  // Stop future alarms
    }
    override fun onBind(intent: Intent): IBinder? {
        return null
    }


    companion object {
        private const val WORK_TAG = "ApiCallWorker"
    }
}
