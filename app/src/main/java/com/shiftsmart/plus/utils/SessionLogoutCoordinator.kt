package com.shiftsmart.plus.utils

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

object SessionLogoutCoordinator {

    private const val TAG = "SessionLogoutCoordinator"
    const val ACTION_FORCE_LOGOUT = "com.shiftsmart.plus.ACTION_FORCE_LOGOUT"
    const val EXTRA_MESSAGE = "extra_force_logout_message"

    private const val DEFAULT_LOGOUT_MESSAGE =
        "Your session is no longer valid. Please login again."

    private const val FORCED_BY_ADMIN_MESSAGE =
        "You have been forcefully logged out by admin. You can no longer use this app."

    private val isTriggered = AtomicBoolean(false)

    @Volatile
    private var pendingMessage: String? = null

    fun handleIfSessionExpired(context: Context, httpCode: Int, responseBody: String?) {
        val (detail, bodyCode) = parseErrorDetail(responseBody)
        // ✅ CHECK FOR 404, 401, OR 403 - ALL SESSION EXPIRY ERRORS
        val isSessionError = httpCode == 404 || httpCode == 401 || httpCode == 403 ||
                             bodyCode == 404 || bodyCode == 401 || bodyCode == 403

        if (!isSessionError) return

        val resolvedMessage = if (detail.equals("LOGOUT", ignoreCase = true)) {
            FORCED_BY_ADMIN_MESSAGE
        } else {
            detail?.takeIf { it.isNotBlank() } ?: DEFAULT_LOGOUT_MESSAGE
        }

        notifyForceLogout(context, resolvedMessage)
    }

    fun consumePendingMessage(): String? {
        val message = pendingMessage
        pendingMessage = null
        return message
    }

    fun resetTrigger() {
        isTriggered.set(false)
        pendingMessage = null
    }

    private fun notifyForceLogout(context: Context, message: String) {
        if (!isTriggered.compareAndSet(false, true)) {
            return
        }

        pendingMessage = message
        Log.w(TAG, "Force logout triggered: $message")

        val intent = Intent(ACTION_FORCE_LOGOUT).apply {
            putExtra(EXTRA_MESSAGE, message)
        }

        // ✅ Use LocalBroadcastManager (same as HomeFragment receiver)
        try {
            LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
            Log.i(TAG, "✅ Logout broadcast sent via LocalBroadcastManager")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending LocalBroadcast logout: ${e.message}")
        }

        // Also send an application-level broadcast so receivers registered
        // with the application/context (e.g., MainActivity) will also receive it.
        try {
            context.sendBroadcast(intent)
            Log.i(TAG, "✅ Logout broadcast also sent via application context")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending app-level logout broadcast: ${e.message}")
        }
    }

    private fun parseErrorDetail(responseBody: String?): Pair<String?, Int?> {
        if (responseBody.isNullOrBlank()) return null to null

        return try {
            val json = JSONObject(responseBody)

            // API shape usually: { "errors": [ { "detail": "...", "code": 404 } ] }
            val errors = json.optJSONArray("errors")
            if (errors != null && errors.length() > 0) {
                val first = errors.optJSONObject(0)
                val detail = first?.optString("detail")?.takeIf { it.isNotBlank() }
                val code = if (first != null && first.has("code")) first.optInt("code") else null
                detail to code
            } else {
                val detail = json.optString("detail")?.takeIf { it.isNotBlank() }
                val code = if (json.has("code")) json.optInt("code") else null
                detail to code
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to parse error body for session handling")
            null to null
        }
    }
}
