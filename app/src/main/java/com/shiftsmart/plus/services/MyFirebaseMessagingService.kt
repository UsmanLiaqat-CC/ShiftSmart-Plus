package com.shiftsmart.plus.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
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

class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        Log.d("MyFirebaseMessagingService", "FromBody: ${remoteMessage.notification?.body}")
        // 🔹 Handle Data Payload
        remoteMessage.data.let { data ->


            val userJson = data["user"] ?: return
            val jsonObject = JSONObject(userJson)
            Log.d("MyFirebaseMessagingService", "Message data payload: $jsonObject")

            val title = remoteMessage.notification?.title ?: "New Update"
            val body = remoteMessage.notification?.body ?: "You have a new notification."

            try {
                val isActive = jsonObject.optBoolean("isActive")

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
            } catch (e: Exception) {
                Log.e("MyFirebaseMessagingService", "Failed to parse user model", e)
            }

            // 🔔 Always show a notification manually (for foreground handling)
            showNotification(title, body)
        }
    }

    private fun showNotification(title: String, message: String) {
        val channelId = "user_updates_channel"
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "User Updates",
                NotificationManager.IMPORTANCE_HIGH
            )
            channel.description = "Channel for user update notifications"
            notificationManager.createNotificationChannel(channel)
        }

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.app_logo) // 🔁 Replace with your own icon
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
    }

    fun handleUserFromNotification(context: Context, user: UserModel) {
        try {

            if (user.isActive) {
                Log.i("MyFirebaseMessagingService", "User is active. Scheduling alarms.")

                val timetable = user.timetable?.range
                val multiTimeTables = user.multipleTimeTables

                if (!timetable.isNullOrEmpty() && !multiTimeTables.isNullOrEmpty()) {
                    AlarmScheduler.scheduleAlarms(
                        context = context,
                        defaultShifts = timetable,
                        multipleTimeTables = multiTimeTables
                    )
                }

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

