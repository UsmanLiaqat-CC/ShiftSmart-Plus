package com.shiftsmart.plus.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.shiftsmart.plus.R
import com.shiftsmart.plus.models.MultipleTimeTable
import com.shiftsmart.plus.models.TimeTable
import com.shiftsmart.plus.models.UserModel
import com.shiftsmart.plus.periodicAction.AlarmScheduler
import com.shiftsmart.plus.utils.SharedPref
import com.shiftsmart.plus.utils.Utils.isServiceRunning
import org.json.JSONObject

enum class NotificationType {
    USER_UPDATE,
    USER_COMPLAINCE,
    REMINDER,
    SHIFT_START,
}

class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        // ✅ CRITICAL: Acquire wake lock IMMEDIATELY to ensure notification processing during Doze Mode
        val wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).run {
            newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "ShiftSmart::FCMWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(5 * 60 * 1000L) // 2 minutes to process notification
            }
        }

        try {
            Log.d("MyFirebaseMessagingService", "📬 FCM received at ${System.currentTimeMillis()}")
            Log.d("MyFirebaseMessagingService", "FromBody: ${remoteMessage.notification?.body}")

            // 🔹 Handle Data Payload
            remoteMessage.data.let { data ->
                val userJson = data["user"] ?: return@let
                val jsonObject = JSONObject(userJson)
                Log.d("MyFirebaseMessagingService", "Message data payload: $jsonObject")

                val title = remoteMessage.notification?.title ?: "New Update"
                val body = remoteMessage.notification?.body ?: "You have a new notification."

                try {
                    val isActive = jsonObject.optBoolean("isActive")
                    val notificationType = jsonObject.optString("type")

                    if (notificationType==NotificationType.REMINDER.name || notificationType==NotificationType.SHIFT_START.name) {
                        Log.e("MyFirebaseMessagingService", "Notification Type: ${notificationType}")
                        val sharedPref = SharedPref.getInstance(context = applicationContext)
                        val user = sharedPref?.getUser()
                        user?.isActive = isActive
                        sharedPref?.saveUser(user) // save updated user
                        if (user != null) {
                            Log.e("MyFirebaseMessagingService", "user not null:${isActive}")
                            handleUserFromNotification(applicationContext, user)
                        }else{
                            Log.e("MyFirebaseMessagingService", "user null")
                        }

                    } else if (notificationType==NotificationType.USER_UPDATE.name) {
                        Log.e("MyFirebaseMessagingService", "Notification Type: USER_UPDATE")
                        val timetableJson = jsonObject.optJSONObject("timetable")?.toString()
                        val timetableModel = Gson().fromJson(timetableJson, TimeTable::class.java)
                        val multiTimeTableJson = jsonObject.optJSONArray("multipleTimeTables")?.toString()
                        val multiTimeTableList: List<MultipleTimeTable> = Gson().fromJson(
                            multiTimeTableJson,
                            object : TypeToken<List<MultipleTimeTable>>() {}.type
                        )
                        val sharedPref = SharedPref.getInstance(context = applicationContext)
                        val user = sharedPref?.getUser()
                        user?.isActive = isActive
                        user?.timetable = timetableModel
                        user?.multipleTimeTables = multiTimeTableList

                        sharedPref?.saveUser(user) // save updated user

                        if (user != null) {
                            Log.e("MyFirebaseMessagingService", "user not null:${isActive}")
                            handleUserFromNotification(applicationContext, user)
                        }else{
                            Log.e("MyFirebaseMessagingService", "user null")
                        }
                    } else if (notificationType == NotificationType.USER_COMPLAINCE.name) {
                        Log.e("MyFirebaseMessagingService", "Notification Type: USER_COMPLAINCE")
                        val sharedPref = SharedPref.getInstance(context = applicationContext)
                        val user = sharedPref?.getUser()

                        if (user != null) {
                            val wasComplaintActive = user.isComplaint
                            val isComplaint = jsonObject.optBoolean("isComplaint", user.isComplaint)
                            user.isComplaint = isComplaint
                            sharedPref.saveUser(user)
                            Log.i("MyFirebaseMessagingService", "Updated user isComplaint=$isComplaint")

                            if (isComplaint) {
                                AlarmScheduler.scheduleComplaintAlarmIfNeeded(
                                    applicationContext,
                                    resetExisting = !wasComplaintActive
                                )
                            } else {
                                AlarmScheduler.cancelComplaintAlarm(applicationContext)
                            }
                        } else {
                            Log.e("MyFirebaseMessagingService", "user null")
                        }
                    }

                    // 🔔 Always show a notification manually (for foreground handling)
                    showNotification(title, body)

                } catch (e: Exception) {
                    Log.e("MyFirebaseMessagingService", "Failed to parse user model", e)
                }
            }
        } finally {
            // ✅ Release wake lock after processing
            if (wakeLock.isHeld) {
                wakeLock.release()
                Log.d("MyFirebaseMessagingService", "Wake lock released")
            }
        }
    }

    private fun showNotification(title: String, message: String) {
        val channelId = "user_updates_channel"
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "New Notification",
                NotificationManager.IMPORTANCE_HIGH
            )
            channel.description = "Channel for user update notifications"
            // Enable sound for the channel
            channel.setSound(
                android.provider.Settings.System.DEFAULT_NOTIFICATION_URI,
                android.media.AudioAttributes.Builder()
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                    .build()
            )
            // Enable vibration
            channel.enableVibration(true)
            channel.vibrationPattern = longArrayOf(0, 1000, 500, 1000)
            // Show notification on lock screen
            channel.lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            channel.setShowBadge(true)
            notificationManager.createNotificationChannel(channel)
        }

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.app_logo) // 🔁 Replace with your own icon
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            // Add sound for devices below Android O
            .setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI)
            // Add vibration pattern
            .setVibrate(longArrayOf(0, 1000, 500, 1000))
            .setDefaults(NotificationCompat.DEFAULT_LIGHTS)
            // Show notification on lock screen
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // Make it a heads-up notification
            .setFullScreenIntent(null, true)

        notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
    }

    fun handleUserFromNotification(context: Context, user: UserModel) {
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

                    val notificationManager = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
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
