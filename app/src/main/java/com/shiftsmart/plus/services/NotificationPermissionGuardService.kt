package com.shiftsmart.plus.services

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationManagerCompat
import com.shiftsmart.plus.utils.DialogManager
import com.shiftsmart.plus.utils.Utils

class NotificationPermissionGuardService : AccessibilityService() {

    private var lastNotificationSettingsOpenAt = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var appStartTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        appStartTime = System.currentTimeMillis()
        Log.i(TAG, "🔌 Notification permission guard connected")
        // ✅ Wait longer on app start to let MainActivity load data first
        scheduleNotificationSettingsCheck(INITIAL_DELAY_MS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // ✅ If still in initial loading phase, wait longer
        val timeSinceStart = System.currentTimeMillis() - appStartTime
        val delay = if (timeSinceStart < INITIAL_DELAY_MS) {
            (INITIAL_DELAY_MS - timeSinceStart).toInt()
        } else {
            ACCESSIBILITY_EVENT_DELAY_MS
        }

        Log.i(TAG, "📱 Accessibility event received (${timeSinceStart}ms since start), rescheduling with ${delay}ms delay")
        handler.removeCallbacks(checkNotificationSettingsRunnable)
        handler.postDelayed(checkNotificationSettingsRunnable, delay.toLong())
    }

    override fun onInterrupt() {
        Log.w(TAG, "⚠️ Notification permission guard interrupted")
    }

    private fun scheduleNotificationSettingsCheck(delayMs: Long = ACCESSIBILITY_EVENT_DELAY_MS) {
        handler.removeCallbacks(checkNotificationSettingsRunnable)
        Log.i(TAG, "📅 Scheduled notification settings check in ${delayMs}ms")
        handler.postDelayed(checkNotificationSettingsRunnable, delayMs)
    }

    private val checkNotificationSettingsRunnable = Runnable {
        openNotificationSettingsIfNeeded()
    }

    private fun openNotificationSettingsIfNeeded() {
        // ✅ Skip if notifications are already granted
        if (Utils.isNotificationPermissionGranted(this)) {
            Log.i(TAG, "✅ Notification permission already granted, skipping")
            return
        }

        // ✅ Skip if any dialog is currently showing (permission, loading, etc.)
        // This is the critical check - includes API calls in progress
        if (DialogManager.isAnyDialogShowing()) {
            Log.i(TAG, "⏳ Dialog/Loading/API already active. Rescheduling in ${RETRY_DELAY_MS}ms...")
            scheduleNotificationSettingsCheck(RETRY_DELAY_MS)
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastNotificationSettingsOpenAt < OPEN_SETTINGS_COOLDOWN_MS) {
            Log.i(TAG, "⏳ Cooldown in progress (${now - lastNotificationSettingsOpenAt}ms/${OPEN_SETTINGS_COOLDOWN_MS}ms), skipping")
            scheduleNotificationSettingsCheck(RETRY_DELAY_MS)
            return
        }
        lastNotificationSettingsOpenAt = now

        try {
            startActivity(buildNotificationSettingsIntent(this))
            Log.i(TAG, "✅ Opened app notification settings because notifications are disabled")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to open notification settings", e)
            scheduleNotificationSettingsCheck(RETRY_DELAY_MS)
        }
    }

    companion object {
        private const val TAG = "NotificationGuard"

        // ✅ Timing constants
        private const val INITIAL_DELAY_MS = 5000L          // 5 seconds - wait for app to load
        private const val ACCESSIBILITY_EVENT_DELAY_MS = 2000L  // 2 seconds - after accessibility event
        private const val RETRY_DELAY_MS = 3000L            // 3 seconds - retry if dialog is showing
        private const val OPEN_SETTINGS_COOLDOWN_MS = 10_000L  // 10 seconds - minimum between opens

        private const val ACTION_ACCESSIBILITY_DETAILS_SETTINGS =
            "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"
        private const val EXTRA_ACCESSIBILITY_COMPONENT_NAME =
            "android.provider.extra.ACCESSIBILITY_COMPONENT_NAME"

        fun isServiceEnabled(context: Context): Boolean {
            val expectedComponent = ComponentName(context, NotificationPermissionGuardService::class.java)
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabledServices)
            while (splitter.hasNext()) {
                val enabledService = splitter.next()
                if (enabledService.equals(expectedComponent.flattenToString(), ignoreCase = true) ||
                    enabledService.equals(expectedComponent.flattenToShortString(), ignoreCase = true)
                ) {
                    return true
                }
            }
            return false
        }

        fun openAccessibilitySettings(context: Context) {
            val componentName = ComponentName(context, NotificationPermissionGuardService::class.java)
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent(ACTION_ACCESSIBILITY_DETAILS_SETTINGS).apply {
                    putExtra(EXTRA_ACCESSIBILITY_COMPONENT_NAME, componentName.flattenToString())
                }
            } else {
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open accessibility details, opening accessibility settings", e)
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }

        fun buildNotificationSettingsIntent(context: Context): Intent {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        }

    }
}
