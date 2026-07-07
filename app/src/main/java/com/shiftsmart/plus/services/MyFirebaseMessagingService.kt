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
import com.shiftsmart.plus.database.ShiftSmartPlusDatabase
import com.shiftsmart.plus.models.MultipleTimeTable
import com.shiftsmart.plus.models.TimeTable
import com.shiftsmart.plus.models.UserModel
import com.shiftsmart.plus.periodicAction.AlarmScheduler
import com.shiftsmart.plus.ui.activities.ComplaintAlertActivity
import com.shiftsmart.plus.ui.activities.MainActivity
import com.shiftsmart.plus.utils.ComplaintAlertNotification
import com.shiftsmart.plus.utils.SharedPref
import com.shiftsmart.plus.utils.Utils.isServiceRunning
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

enum class NotificationType {
    USER_UPDATE,
    USER_COMPLAINCE,
    REMINDER,
    SHIFT_START,
}

class MyFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "MyFirebaseMsgService"
    }

    /**
     * Called for ALL FCM messages regardless of app state (foreground, background, killed).
     * We use this to handle USER_UPDATE payload updates in SharedPreferences before Firebase
     * decides whether to call onMessageReceived (foreground) or show a system notification
     * (background/killed). This ensures timetable & isActive are always updated from FCM payload.
     */
    override fun handleIntent(intent: android.content.Intent) {
        try {
            val userJson = intent.getStringExtra("user")
            if (!userJson.isNullOrBlank()) {
                val jsonObject = runCatching { JSONObject(userJson) }.getOrNull()
                if (jsonObject != null) {
                    val type = jsonObject.optString("type")
                    if (type == NotificationType.USER_UPDATE.name) {
                        Log.i(TAG, "🔔 handleIntent: USER_UPDATE detected — applying SharedPrefs update from FCM payload (all states)")
                        applyUserUpdateFromPayload(jsonObject)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleIntent: unexpected error during USER_UPDATE processing", e)
        }
        super.handleIntent(intent)
    }

    /**
     * Applies timetable, multipleTimeTables and isActive from a USER_UPDATE FCM data payload
     * directly into SharedPreferences, then reschedules/stops alarms accordingly.
     * Safe to call from any thread; no Activity context needed.
     */
    private fun applyUserUpdateFromPayload(jsonObject: JSONObject) {
        val sharedPref = SharedPref.getInstance(applicationContext) ?: run {
            Log.w(TAG, "applyUserUpdateFromPayload: SharedPref unavailable, skipping")
            return
        }
        val user = sharedPref.getUser() ?: run {
            Log.w(TAG, "applyUserUpdateFromPayload: no cached user in SharedPrefs, skipping")
            return
        }

        val isActive = jsonObject.optBoolean("isActive", user.isActive)

        val timetableJson = jsonObject.optJSONObject("timetable")?.toString()
        val timetableModel = if (!timetableJson.isNullOrBlank()) {
            runCatching { Gson().fromJson(timetableJson, TimeTable::class.java) }.getOrElse {
                Log.w(TAG, "applyUserUpdateFromPayload: failed to parse timetable: ${it.message}")
                user.timetable
            }
        } else user.timetable

        val multiTimeTableJson = jsonObject.optJSONArray("multipleTimeTables")?.toString()
        val multiTimeTableList: List<MultipleTimeTable> = if (!multiTimeTableJson.isNullOrBlank()) {
            runCatching<List<MultipleTimeTable>> {
                Gson().fromJson(
                    multiTimeTableJson,
                    object : TypeToken<List<MultipleTimeTable>>() {}.type
                )
            }.getOrElse {
                Log.w(TAG, "applyUserUpdateFromPayload: failed to parse multipleTimeTables: ${it.message}")
                user.multipleTimeTables ?: emptyList()
            }
        } else user.multipleTimeTables ?: emptyList()

        user.isActive = isActive
        user.timetable = timetableModel
        user.multipleTimeTables = multiTimeTableList
        sharedPref.saveUser(user)

        Log.i(
            TAG,
            "✅ USER_UPDATE applied to SharedPrefs: isActive=$isActive | " +
            "timetable=${timetableModel?.timeTableName} | " +
            "multiTimeTables=${multiTimeTableList.size}"
        )

        // Reschedule or stop the shift service based on updated isActive state
        handleUserFromNotification(applicationContext, user)
    }

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
            Log.e("MyFirebaseMessagingService", "📬 FCM received at ${System.currentTimeMillis()}")
            Log.e("MyFirebaseMessagingService", "📦 notification.title=${remoteMessage.notification?.title} | notification.body=${remoteMessage.notification?.body}")
            Log.e("MyFirebaseMessagingService", "🗃️ data map (${remoteMessage.data.size} keys): ${remoteMessage.data.entries.joinToString { "${it.key}=${it.value}" }}")
            Log.e(
                "MyFirebaseMessagingService",
                "📬 messageId=${remoteMessage.messageId} | from=${remoteMessage.from} | sentTime=${remoteMessage.sentTime} | ttl=${remoteMessage.ttl}"
            )
            val fullDataJson = JSONObject(remoteMessage.data as Map<*, *>).toString(2)
            Log.e("MyFirebaseMessagingService", "🧾 full data payload JSON:\n$fullDataJson")

            remoteMessage.notification?.let { notification ->
                Log.e(
                    "MyFirebaseMessagingService",
                    "📦 full notification payload -> title=${notification.title}, body=${notification.body}, channelId=${notification.channelId}, clickAction=${notification.clickAction}, imageUrl=${notification.imageUrl}, sound=${notification.sound}"
                )
            } ?: Log.e("MyFirebaseMessagingService", "📦 full notification payload -> null (data-only push)")

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

                // ── Parse the nested `payload` field the server sends ──────────────────────
                // Server structure: data: { payload: '{"type":"USER_COMPLIANCE","userComplianceRecordId":"...","timestamp":"..."}' }
                val payloadJson = data["payload"]
                val payloadObject = runCatching {
                    if (payloadJson.isNullOrBlank()) JSONObject() else JSONObject(payloadJson)
                }.getOrElse { JSONObject() }
                if (payloadJson.isNullOrBlank()) {
                    Log.e("MyFirebaseMessagingService", "🗃️ data.payload raw: <empty>")
                } else {
                    Log.e("MyFirebaseMessagingService", "🗃️ data.payload raw: $payloadJson")
                    Log.e("MyFirebaseMessagingService", "🗃️ data.payload pretty:\n${payloadObject.toString(2)}")
                }
                Log.e("MyFirebaseMessagingService", "🗃️ data.payload parsed: $payloadObject")

                // Prefer data payload fields so this also works for data-only FCM messages
                // (required for reliable background delivery to onMessageReceived).
                // Fall back through: data["title"] → notification.title → user JSON → payloadObject → default string
                val title = data["title"]
                    ?: remoteMessage.notification?.title
                    ?: jsonObject.optString("title").takeIf { it.isNotBlank() }
                    ?: payloadObject.optString("title").takeIf { it.isNotBlank() }
                    ?: ""
                val body = data["body"]
                    ?: remoteMessage.notification?.body
                    ?: jsonObject.optString("body").takeIf { it.isNotBlank() }
                    ?: payloadObject.optString("body").takeIf { it.isNotBlank() }
                    ?: payloadObject.optString("message").takeIf { it.isNotBlank() }
                    ?: ""

                // Global safety: if backend sends "Removed from Compliance" in title,
                // always cancel complaint alarms immediately.
                if (isRemovedFromComplianceTitle(title)) {
                    forceCancelComplaintFromTitle(applicationContext, title)
                }

                try {
                    val isActive = data["isActive"]?.toBooleanStrictOrNull() ?: jsonObject.optBoolean("isActive")
                    // Type: check flat data → userJson → payloadObject
                    val notificationType = data["type"]
                        ?: jsonObject.optString("type").takeIf { it.isNotBlank() }
                        ?: payloadObject.optString("type").takeIf { it.isNotBlank() }
                        ?: ""
                    val complaintFlag = extractComplaintFlag(
                        data = data,
                        jsonObject = jsonObject,
                        payloadObject = payloadObject,
                        title = title,
                        body = body
                    )

                Log.e("MyFirebaseMessagingService", "🔍 title='$title' | body='$body' | type='$notificationType' | complaintFlag=$complaintFlag")

                    // ── Compliance detection ──────────────────────────────────────────────────
                    // Detect compliance notification even when `type` or `isCompliant` are absent,
                    // using presence of compliance record ID or title/body keywords as signals.
                    val hasComplianceRecordId =
                        !data["userComplianceRecordId"].isNullOrBlank() ||
                        !data["complianceRecordId"].isNullOrBlank() ||
                        payloadObject.optString("userComplianceRecordId").isNotBlank() ||
                        payloadObject.optString("complianceRecordId").isNotBlank()
                    val textLower = "$title $body".lowercase()
                    val isComplianceByText = textLower.contains("compliance") &&
                        !textLower.contains("removed") && !textLower.contains("disabled")

                    val detectedAsCompliance =
                        notificationType.equals("USER_COMPLAINCE", ignoreCase = true) ||
                        notificationType.equals("USER_COMPLIANCE", ignoreCase = true) ||
                        hasComplianceRecordId ||
                        (isComplianceByText && complaintFlag != false)

                    Log.e("MyFirebaseMessagingService", "🔎 hasComplianceRecordId=$hasComplianceRecordId | isComplianceByText=$isComplianceByText | detectedAsCompliance=$detectedAsCompliance")

                    if (detectedAsCompliance) {
                        if (complaintFlag == false) {
                            // Compliance was explicitly cancelled
                            val sharedPref = SharedPref.getInstance(context = applicationContext)
                            sharedPref?.saveIsComplaintActive(false)
                            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                            nm.cancel(9110)
                            Log.e("MyFirebaseMessagingService", "Compliance cancelled (isComplaint=false)")
                        } else {
                            // Active compliance — increment badge and show full-screen alert
                            val sharedPref = SharedPref.getInstance(context = applicationContext)
                            sharedPref?.saveIsComplaintActive(true)
                            sharedPref?.incrementComplianceBadgeCount()
                            sharedPref?.saveLastComplianceNotificationTime(System.currentTimeMillis())
                            androidx.localbroadcastmanager.content.LocalBroadcastManager
                                .getInstance(applicationContext)
                                .sendBroadcast(android.content.Intent(com.shiftsmart.plus.ui.activities.MainActivity.ACTION_COMPLIANCE_BADGE_UPDATED))
                            val complianceRecordId = data["userComplianceRecordId"]
                                ?: data["complianceRecordId"]
                                ?: payloadObject.optString("userComplianceRecordId").takeIf { it.isNotBlank() }
                                ?: payloadObject.optString("complianceRecordId").takeIf { it.isNotBlank() }
                                ?: ""
                            val sentAtMs = parseIsoTimestamp(
                                data["timestamp"] ?: payloadObject.optString("timestamp").takeIf { it.isNotBlank() }
                            )
                            if (title.isBlank() || body.isBlank() || complianceRecordId.isBlank()) {
                                Log.w(
                                    "MyFirebaseMessagingService",
                                    "Skipping compliance full-screen notification: missing title/body/complianceRecordId"
                                )
                            } else {
                                showComplianceNotification(title, body, complianceRecordId, sentAtMs)
                            }
                            Log.e("MyFirebaseMessagingService", "✅ Compliance: badge incremented, ComplaintAlertActivity notification shown")
                        }
                        return@let  // handled — skip the type-based branches below
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
                            // Notify HomeFragment in real-time so the badge updates immediately
                            // if the screen is currently visible.
                            androidx.localbroadcastmanager.content.LocalBroadcastManager
                                .getInstance(applicationContext)
                                .sendBroadcast(android.content.Intent(com.shiftsmart.plus.ui.activities.MainActivity.ACTION_COMPLIANCE_BADGE_UPDATED))
                        } else {
                            // ✅ IMMEDIATELY cancel alarm (no user tap needed)
                            AlarmScheduler.cancelComplaintAlarm(applicationContext)

                            // ✅ Dismiss complaint notification when complaint becomes inactive.
                            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                            notificationManager.cancel(9110) // ComplaintAlertNotification.NOTIFICATION_ID
                            Log.i("MyFirebaseMessagingService", "✅ ALARM AUTO-CANCELED + NOTIFICATION DISMISSED (NO TAP NEEDED)")
                        }
                    }

                    // 🔔 Show notification
                    if (notificationType == NotificationType.USER_COMPLAINCE.name && complaintFlag == true) {
                        // Active compliance → tap opens ComplaintAlertActivity with real content
                        val complianceRecordId = data["userComplianceRecordId"]
                            ?: data["complianceRecordId"] ?: ""
                        val sentAtMs = parseIsoTimestamp(data["timestamp"])
                        if (title.isBlank() || body.isBlank() || complianceRecordId.isBlank()) {
                            Log.w(
                                "MyFirebaseMessagingService",
                                "Skipping compliance full-screen notification: missing title/body/complianceRecordId"
                            )
                        } else {
                            showComplianceNotification(title, body, complianceRecordId, sentAtMs)
                            Log.i("MyFirebaseMessagingService", "📢 Compliance notification shown → ComplaintAlertActivity")
                        }
                    } else if (notificationType != NotificationType.USER_COMPLAINCE.name) {
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

    private fun showComplianceNotification(
        title: String,
        body: String,
        complianceRecordId: String,
        sentAtMs: Long
    ) {
        val channelId = "user_updates_channel"
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "New Notification", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Channel for user update notifications"
                setSound(
                    android.provider.Settings.System.DEFAULT_NOTIFICATION_URI,
                    android.media.AudioAttributes.Builder()
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                        .build()
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 1000, 500, 1000)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val alertIntent = Intent(this, ComplaintAlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(ComplaintAlertActivity.EXTRA_TITLE, title)
            putExtra(ComplaintAlertActivity.EXTRA_DESCRIPTION, body)
            putExtra(ComplaintAlertActivity.EXTRA_SENT_AT_MS, sentAtMs)
            putExtra(MainActivity.EXTRA_COMPLIANCE_RECORD_ID, complianceRecordId)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, System.currentTimeMillis().toInt(), alertIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.app_logo)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI)
            .setVibrate(longArrayOf(0, 1000, 500, 1000))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true)  // heads-up / lock-screen full-screen
            .build()

        notificationManager.notify(ComplaintAlertNotification.NOTIFICATION_ID, notification)
    }

    /** Parse an ISO-8601 UTC string ("2026-06-19T09:36:59.678Z") to epoch milliseconds, or 0 on failure. */
    private fun parseIsoTimestamp(iso: String?): Long {
        if (iso.isNullOrBlank()) return 0L
        return try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
            sdf.parse(iso)?.time ?: run {
                // Retry without milliseconds
                val sdf2 = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                sdf2.timeZone = java.util.TimeZone.getTimeZone("UTC")
                sdf2.parse(iso)?.time ?: 0L
            }
        } catch (e: Exception) { 0L }
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
        payloadObject: JSONObject = JSONObject(),
        title: String,
        body: String
    ): Boolean? {
        data["isComplaint"]?.toBooleanStrictOrNull()?.let { return it }
        if (jsonObject.has("isComplaint")) {
            return jsonObject.optBoolean("isComplaint")
        }
        if (payloadObject.has("isComplaint")) {
            return payloadObject.optBoolean("isComplaint")
        }

        // If the payload type is USER_COMPLIANCE/USER_COMPLAINCE treat as active compliance
        val payloadType = payloadObject.optString("type")
        if (payloadType.equals("USER_COMPLIANCE", ignoreCase = true) ||
            payloadType.equals("USER_COMPLAINCE", ignoreCase = true)) {
            return true
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
