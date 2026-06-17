package com.shiftsmart.plus.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
import com.shiftsmart.plus.ui.activities.MainActivity
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
            Log.d("MyFirebaseMessagingService", "FromBody: ${remoteMessage.notification?.body}  --> ${remoteMessage.notification?.title}")

            if (remoteMessage.notification != null) {
                Log.w(
                    "MyFirebaseMessagingService",
                    "FCM contains notification payload. For complaint auto-enable/disable in background/closed state, use data-only payload for complaint updates."
                )
            }

            // 🔹 Handle Data Payload (supports both nested `user` JSON and flat key/value payloads)
            remoteMessage.data.let { data ->
                val userJson = data["user"]
                val jsonObject = runCatching {
                    if (userJson.isNullOrBlank()) JSONObject() else JSONObject(userJson)
                }.getOrElse {
                    Log.e("MyFirebaseMessagingService", "Invalid user payload JSON", it)
                    JSONObject()
                }
                Log.d("MyFirebaseMessagingService", "Message data payload: $jsonObject")

                // Prefer data payload fields so this also works for data-only FCM messages
                // (required for reliable background delivery to onMessageReceived).
                val title = data["title"] ?: remoteMessage.notification?.title ?: "New Update"
                val body = data["body"] ?: remoteMessage.notification?.body ?: "You have a new notification."

                // Global safety: if backend sends "Removed from Compliance" in title,
                // always cancel complaint alarms immediately.
                if (isRemovedFromComplianceTitle(title)) {
                    forceCancelComplaintFromTitle(applicationContext, title)
                }

                try {
                    val isActive = data["isActive"]?.toBooleanStrictOrNull() ?: jsonObject.optBoolean("isActive")
                    val notificationType = data["type"] ?: jsonObject.optString("type")
                    val complaintFlag = extractComplaintFlag(
                        data = data,
                        jsonObject = jsonObject,
                        title = title,
                        body = body
                    )

                    // Fallback for payloads missing `type` but still carrying isComplaint state.
                    if (notificationType.isBlank() && complaintFlag != null) {
                        val sharedPref = SharedPref.getInstance(context = applicationContext)
                        sharedPref?.saveIsComplaintActive(complaintFlag)
                        if (!complaintFlag) {
                            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                            notificationManager.cancel(9110)
                        }
                        Log.i("MyFirebaseMessagingService", "Fallback complaint sync from payload without type: isComplaint=$complaintFlag")
                    }

                    if (notificationType==NotificationType.REMINDER.name || notificationType==NotificationType.SHIFT_START.name) {
                        Log.e("MyFirebaseMessagingService", "Notification Type: ${notificationType}")
                        val sharedPref = SharedPref.getInstance(context = applicationContext)
                        val user = sharedPref?.getUser()
                        user?.isActive = isActive
                        user?.let { sharedPref?.saveUser(it) } // save updated user only when available
                        if (user != null) {
                            Log.e("MyFirebaseMessagingService", "user not null:${isActive}")
                            handleUserFromNotification(applicationContext, user)
                        }else{
                            Log.e("MyFirebaseMessagingService", "user null")
                        }

                    } else if (notificationType==NotificationType.USER_UPDATE.name)
                    {
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

                        // Some backends send complaint status changes via USER_UPDATE.
                        // Keep complaint state in sync here too, otherwise stale alarms can keep firing.
                        var shouldCancelComplaintAlarm = false
                        var shouldScheduleComplaintAlarm = false
                        if (complaintFlag != null) {
                            val wasComplaintActive = user?.isComplaint
                                ?: (sharedPref?.getIsComplaintActive() ?: false)
                            val isComplaint = complaintFlag
                            user?.isComplaint = isComplaint
                            sharedPref?.saveIsComplaintActive(isComplaint)
                            shouldCancelComplaintAlarm = !isComplaint
                            shouldScheduleComplaintAlarm = isComplaint && !wasComplaintActive
                            Log.i(
                                "MyFirebaseMessagingService",
                                "USER_UPDATE carried isComplaint=$isComplaint (hadUser=${user != null})"
                            )
                        }

                        user?.let { sharedPref?.saveUser(it) } // save updated user only when available

                        if (shouldCancelComplaintAlarm) {
                            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                            notificationManager.cancel(9110)
                            Log.i("MyFirebaseMessagingService", "✅ USER_UPDATE removed complaint; dismissed notification")
                        }

                        if (user != null) {
                            Log.e("MyFirebaseMessagingService", "user not null:${isActive}")
                            handleUserFromNotification(applicationContext, user)
                        }else{
                            Log.e("MyFirebaseMessagingService", "user null")
                        }
                    } else if (notificationType == NotificationType.USER_COMPLAINCE.name) {
                        Log.e("MyFirebaseMessagingService", "Notification Type: USER_COMPLAINCE")
                        if (remoteMessage.notification != null) {
                            Log.e(
                                "MyFirebaseMessagingService",
                                "UNSAFE PAYLOAD: USER_COMPLAINCE with notification object. Android may show tray notification but skip onMessageReceived in background; alarm cancel may be missed."
                            )
                        }
                        
                        val sharedPref = SharedPref.getInstance(context = applicationContext)
                        var user = sharedPref?.getUser()
                        if (complaintFlag == null) {
                            Log.w(
                                "MyFirebaseMessagingService",
                                "USER_COMPLAINCE without isComplaint in payload; skipping complaint state change"
                            )
                            return@let
                        }
                        val wasComplaintActive = user?.isComplaint ?: (sharedPref?.getIsComplaintActive() ?: false)
                        val isComplaint = complaintFlag

                        // If user is missing (possible in background process), rebuild from payload if available.
                        if (user == null) {
                            user = runCatching {
                                if (userJson.isNullOrBlank()) null else Gson().fromJson(userJson, UserModel::class.java)
                            }.getOrNull()
                        }

                        user?.let {
                            it.isComplaint = isComplaint
                            sharedPref?.saveUser(it)
                        }

                        // ✅ CRITICAL: Persist sync flag with commit() so cancellation/scheduling survives process death.
                        sharedPref?.saveIsComplaintActive(isComplaint)
                        Log.i(
                            "MyFirebaseMessagingService",
                            "Updated complaint state: isComplaint=$isComplaint, hadUser=${user != null}"
                        )

                        if (isComplaint) {
                            // Track badge count for notification icon
                            sharedPref?.incrementComplianceBadgeCount()
                            sharedPref?.saveLastComplianceNotificationTime(System.currentTimeMillis())
                            Log.i("MyFirebaseMessagingService", "✅ Compliance badge incremented")
                        } else {
                            // ✅ IMMEDIATELY cancel alarm (no user tap needed)
                            AlarmScheduler.cancelComplaintAlarm(applicationContext)

                            // ✅ Dismiss complaint notification when complaint becomes inactive.
                            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                            notificationManager.cancel(9110) // ComplaintAlertNotification.NOTIFICATION_ID
                            Log.i("MyFirebaseMessagingService", "✅ ALARM AUTO-CANCELED + NOTIFICATION DISMISSED (NO TAP NEEDED)")
                        }
                    }

                    // 🔔 Show notification (for foreground and as system tray entry when backgrounded)
                    // For complaint notifications, include data so system can process even if closed
                    if (notificationType == NotificationType.USER_COMPLAINCE.name && complaintFlag != null) {
                        val complaintData = ComplaintNotificationData(
                            userJson = userJson,
                            isComplaint = complaintFlag,
                            type = notificationType
                        )
                        showNotification(
                            title,
                            "Complaint status updated: ${if (complaintFlag) "ENABLED" else "DISABLED"} (Auto-scheduled)",
                            complaintData
                        )
                        Log.i("MyFirebaseMessagingService", "📢 Complaint notification shown with auto-action data")
                    } else {
                        showNotification(title, body)
                    }

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

    private fun showNotification(title: String, message: String, complaintData: ComplaintNotificationData? = null) {
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

        // ✅ CRITICAL FIX: Create proper intent to handle notification click
        // This prevents the "sealed instance" exception when clicking notification after unlock
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            action = Intent.ACTION_MAIN
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

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
            // ✅ Set content intent to properly handle notification click
            .setContentIntent(pendingIntent)
            // Make it a heads-up notification
            .setFullScreenIntent(null, true)

        notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
    }

    // Data class to pass complaint info to notification handler
    data class ComplaintNotificationData(
        val userJson: String?,
        val isComplaint: Boolean,
        val type: String
    )

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

    private fun extractComplaintFlag(
        data: Map<String, String>,
        jsonObject: JSONObject,
        title: String,
        body: String
    ): Boolean? {
        data["isComplaint"]?.toBooleanStrictOrNull()?.let { return it }
        if (jsonObject.has("isComplaint")) {
            return jsonObject.optBoolean("isComplaint")
        }

        // Defensive fallback for backends that only send complaint state in text.
        val text = ("$title $body").lowercase(Locale.getDefault())
        if (text.contains("removed from compliance") ||
            text.contains("complaint removed") ||
            text.contains("complaint disabled")
        ) {
            return false
        }
        if (text.contains("added to compliance") ||
            text.contains("complaint added") ||
            text.contains("complaint enabled")
        ) {
            return true
        }

        return null
    }

    private fun isRemovedFromComplianceTitle(title: String): Boolean {
        return title.trim().lowercase(Locale.getDefault()).contains("removed from compliance")
    }

    private fun forceCancelComplaintFromTitle(context: Context, title: String) {
        val sharedPref = SharedPref.getInstance(context = context)
        sharedPref?.saveIsComplaintActive(false)

        sharedPref?.getUser()?.let { user ->
            user.isComplaint = false
            sharedPref.saveUser(user)
        }

        AlarmScheduler.cancelComplaintAlarm(context)
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(9110)

        Log.i(
            "MyFirebaseMessagingService",
            "✅ FORCE CANCELED complaint alarms due to title match: '$title'"
        )
    }

    /**
     * Converts timestamp in milliseconds to human-readable format.
     * Example: 1780653207687 → "Wed Jun 05 2026, 2:30 PM IST"
     */
    private fun formatTimestampReadable(timestampMs: Long): String {
        return try {
            val formatter = SimpleDateFormat("EEE MMM dd yyyy, h:mm a z", Locale.getDefault())
            val date = Date(timestampMs)
            formatter.format(date)
        } catch (e: Exception) {
            "Invalid timestamp"
        }
    }

    /**
     * Converts duration in milliseconds to human-readable format.
     * Example: 119998ms → "1 min 59 sec"
     */
    private fun formatDurationReadable(durationMs: Long): String {
        return try {
            val totalSeconds = durationMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            when {
                minutes > 0 -> "$minutes min $seconds sec"
                else -> "$seconds sec"
            }
        } catch (e: Exception) {
            "Invalid duration"
        }
    }

}
