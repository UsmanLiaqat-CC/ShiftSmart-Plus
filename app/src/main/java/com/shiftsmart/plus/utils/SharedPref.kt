package com.shiftsmart.plus.utils

import android.content.Context
import com.google.gson.Gson
import com.shiftsmart.plus.models.UserModel

/**
 * Created by Usman Liaqat on 28,Jan,2025
 * usmanliaqat@codecoytechnologies.com,
 * CodeCoy Technologies,
 * Lahore, Pakistan.
 */
class SharedPref(private val ctx: Context) {

    private val USER = "user"
    private val TOKEN = "token"
    private val FINGERPRINT = "fingerprint"
    private val LAST_SYNC_TIME = "last_sync_time" // Format: "2025-10-28 07:10:00" (with date)
    private val LAST_SYNC_TIMESTAMP = "last_sync_timestamp" // Unix timestamp in milliseconds
    private val COMPLAINT_ALERT_TRIGGER_TIME = "complaint_alert_trigger_time"
    private val IS_COMPLAINT_ACTIVE = "is_complaint_active"
    private val COMPLIANCE_BADGE_COUNT = "compliance_badge_count"
    private val LAST_COMPLIANCE_NOTIFICATION_TIME = "last_compliance_notification_time"
    private val SUBSCRIBED_FCM_USER_ID = "subscribed_fcm_user_id"

    // Save Token
    fun saveToken(token: String?) {
        sharedPreferences.edit().apply {
            putString(TOKEN, token)
        }.apply()
    }

    // Get Token
    fun getToken(): String? {
        return sharedPreferences.getString(TOKEN, null)
    }

    fun saveUser(userProfile: UserModel?) {
        val gson = Gson()
        val json = gson.toJson(userProfile)
        sharedPreferences.edit().apply {
            putString(USER, json)
        }.apply()
    }

    fun getUser(): UserModel? {
        val gson = Gson()
        val json: String? = sharedPreferences.getString(USER, null)
        return if (json != null) {
            val obj: UserModel = gson.fromJson(json, UserModel::class.java)
            obj
        } else {
            null
        }

    }

    /**
     * Save last successful sync time with FULL date and time.
     * This is critical for overnight shifts that span midnight.
     *
     * Stores two values:
     * 1. Full date-time string: "2025-10-28 07:10:00" (for debugging)
     * 2. Unix timestamp: milliseconds since epoch (for accurate calculations)
     *
     * @param localTime Format: "HH:mm:ss" (e.g., "07:10:00")
     */
    fun saveLastSyncTime(localTime: String) {
        try {
            // Get current date and time
            val currentDateTime = java.util.Calendar.getInstance()

            // Parse the time components
            val timeParts = localTime.split(":")
            if (timeParts.size >= 2) {
                val hour = timeParts[0].toIntOrNull() ?: 0
                val minute = timeParts[1].toIntOrNull() ?: 0
                // ✅ ALWAYS set seconds to 0 to align with 5-minute boundaries
                // This prevents misalignment issues with alarm scheduling

                // Set the time on today's date
                currentDateTime.set(java.util.Calendar.HOUR_OF_DAY, hour)
                currentDateTime.set(java.util.Calendar.MINUTE, minute)
                currentDateTime.set(java.util.Calendar.SECOND, 0)  // Always 0 for exact boundary
                currentDateTime.set(java.util.Calendar.MILLISECOND, 0)

                // Format as "yyyy-MM-dd HH:mm:ss"
                val dateTimeFormatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                val dateTimeString = dateTimeFormatter.format(currentDateTime.time)

                // Save both the formatted string and the timestamp
                sharedPreferences.edit().apply {
                    putString(LAST_SYNC_TIME, dateTimeString)
                    putLong(LAST_SYNC_TIMESTAMP, currentDateTime.timeInMillis)
                }.apply()

                android.util.Log.i("SharedPref", "💾 Saved last sync: $dateTimeString (timestamp: ${currentDateTime.timeInMillis})")
            }
        } catch (e: Exception) {
            android.util.Log.e("SharedPref", "Error saving last sync time", e)
        }
    }

    /**
     * Clear last sync data (called on logout or service stop)
     */
    fun clearLastSyncTime() {
        sharedPreferences.edit().apply {
            remove(LAST_SYNC_TIME)
            remove(LAST_SYNC_TIMESTAMP)
        }.apply()
        android.util.Log.i("SharedPref", "🗑️ Cleared last sync data")
    }



    /**
     * Get last successful sync as Unix timestamp (milliseconds).
     * This is the RECOMMENDED method for gap calculations as it handles:
     * - Overnight shifts (crossing midnight)
     * - Date changes
     * - Multi-day gaps
     *
     * @return Timestamp in milliseconds, or 0L if no sync recorded
     */
    fun getLastSyncTimestamp(): Long {
        return sharedPreferences.getLong(LAST_SYNC_TIMESTAMP, 0L)
    }

    /**
     * Get last successful sync as full date-time string.
     * Format: "2025-10-28 07:10:00"
     *
     * @return Full date-time string, or null if no sync recorded
     */
    fun getLastSyncDateTime(): String? {
        return sharedPreferences.getString(LAST_SYNC_TIME, null)
    }

    fun saveComplaintAlertTriggerTime(triggerTime: Long) {
        sharedPreferences.edit()
            .putLong(COMPLAINT_ALERT_TRIGGER_TIME, triggerTime)
            .apply()
    }

    fun getComplaintAlertTriggerTime(): Long {
        return sharedPreferences.getLong(COMPLAINT_ALERT_TRIGGER_TIME, 0L)
    }

    fun clearComplaintAlertTriggerTime() {
        sharedPreferences.edit()
            .remove(COMPLAINT_ALERT_TRIGGER_TIME)
            .apply()
    }

    /**
     * Synchronously persist the complaint-active flag.
     * Uses commit() so the write is on disk before the FCM process is killed,
     * preventing a stale isComplaint=true being read by the AlarmReceiver.
     */
    fun saveIsComplaintActive(active: Boolean) {
        sharedPreferences.edit()
            .putBoolean(IS_COMPLAINT_ACTIVE, active)
            .commit()  // synchronous — must survive process death
        android.util.Log.i("SharedPref", "✅ Saved isComplaintActive=$active (sync)")
    }

    fun getIsComplaintActive(): Boolean {
        return sharedPreferences.getBoolean(IS_COMPLAINT_ACTIVE, true) // default true = safe (won't suppress real alerts)
    }

    // ─── Compliance notification badge ──────────────────────────────────────────

    fun getComplianceBadgeCount(): Int =
        sharedPreferences.getInt(COMPLIANCE_BADGE_COUNT, 0)

    fun incrementComplianceBadgeCount() {
        val current = getComplianceBadgeCount()
        sharedPreferences.edit().putInt(COMPLIANCE_BADGE_COUNT, current + 1).apply()
    }

    fun resetComplianceBadgeCount() {
        sharedPreferences.edit().putInt(COMPLIANCE_BADGE_COUNT, 0).apply()
    }

    /** Save the time (epoch ms) when a USER_COMPLAINCE notification was received. */
    fun saveLastComplianceNotificationTime(timestampMs: Long) {
        sharedPreferences.edit().putLong(LAST_COMPLIANCE_NOTIFICATION_TIME, timestampMs).apply()
    }

    fun getLastComplianceNotificationTime(): Long =
        sharedPreferences.getLong(LAST_COMPLIANCE_NOTIFICATION_TIME, 0L)

    /** Returns the user ID that is currently subscribed to an FCM topic, or null if none. */
    fun getSubscribedFcmUserId(): String? =
        sharedPreferences.getString(SUBSCRIBED_FCM_USER_ID, null)

    /** Persists the user ID that has been subscribed so we can skip redundant subscribe calls. */
    fun saveSubscribedFcmUserId(userId: String) {
        sharedPreferences.edit().putString(SUBSCRIBED_FCM_USER_ID, userId).apply()
    }


    // 🔐 Save fingerprint enable/disable state
    fun setFingerprintEnabled(enabled: Boolean) {
        sharedPreferences.edit().putBoolean(FINGERPRINT, enabled).apply()
    }

    // 🔐 Get fingerprint state
    fun isFingerprintEnabled(): Boolean {
        return sharedPreferences.getBoolean(FINGERPRINT, false)
    }


    fun clearPrefrence() {
        sharedPreferences?.edit()?.clear()?.apply()
    }

    val sharedPreferences = ctx.getSharedPreferences(PREFERENCE, 0)


    companion object {
        private var instance: SharedPref? = null
        var PREFERENCE = "ShiftSmart Plus"

        fun getInstance(context: Context): SharedPref? {
            if (instance == null) {
                instance = SharedPref(context)
            }
            return instance
        }
    }

}
