package com.shiftsmart.plus.periodicAction

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.UserManager
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.shiftsmart.plus.services.MyService
import com.shiftsmart.plus.utils.SharedPref
import com.shiftsmart.plus.utils.Utils
import com.shiftsmart.plus.utils.Utils.isServiceRunning

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i("BootReceiver", "onReceive: $action → reschedule if needed")

        // We’ll act on reboot and clock changes
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_TIME_CHANGED &&
            action != Intent.ACTION_TIMEZONE_CHANGED
        ) {
            return
        }

        val appContext = context.applicationContext

        // ⚠️ Before user unlock, credential-protected storage (SharedPref) may be unavailable
        // on FBE-encrypted devices. Defer the reschedule until ACTION_USER_UNLOCKED instead of
        // silently giving up, so shift alarms still get armed after an early boot.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val um = appContext.getSystemService(UserManager::class.java)
            if (um != null && !um.isUserUnlocked) {
                Log.w("BootReceiver", "User not unlocked yet. Waiting for ACTION_USER_UNLOCKED to reschedule.")
                waitForUnlockThenReschedule(appContext)
                return
            }
        }

        rescheduleShiftAlarms(appContext)
    }

    /**
     * Registers a one-shot receiver for ACTION_USER_UNLOCKED so FBE-encrypted devices
     * still get their shift alarms rearmed once credential storage becomes readable.
     */
    private fun waitForUnlockThenReschedule(appContext: Context) {
        val unlockReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, unlockIntent: Intent) {
                if (unlockIntent.action == Intent.ACTION_USER_UNLOCKED) {
                    try {
                        appContext.unregisterReceiver(this)
                    } catch (e: Exception) {
                        Log.w("BootReceiver", "unregisterReceiver failed", e)
                    }
                    rescheduleShiftAlarms(appContext)
                }
            }
        }
        appContext.registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_UNLOCKED))
    }

    /**
     * Unconditionally (re)arms TODAY's and TOMORROW's shift START/STOP alarms.
     * AlarmManager drops all alarms on reboot, so this must run every boot regardless of
     * whether we're currently inside a shift window — otherwise a shift starting later that
     * day would never auto-start until the user manually opens the app.
     */
    private fun rescheduleShiftAlarms(appContext: Context) {
        try {
            val user = SharedPref.getInstance(appContext)?.getUser()
            Log.i("BootReceiver", "Retrieved user from SharedPref: $user")

            val defaultShifts = user?.timetable?.range ?: emptyList()
            if (user != null && defaultShifts.isNotEmpty()) {
                Log.i("BootReceiver", "🔁 Rescheduling today+tomorrow shift alarms after boot/time change")
                AlarmScheduler.scheduleTodayAndTomorrow(
                    appContext,
                    defaultShifts,
                    user.multipleTimeTables ?: emptyList()
                )
            } else {
                Log.i("BootReceiver", "No logged-in user/timetable found - nothing to schedule")
            }

            // Complaint alarm scheduling removed (no longer needed)
            val sharedPref = SharedPref.getInstance(appContext)
            val isComplaintActive = sharedPref?.getIsComplaintActive() ?: false
            if (isComplaintActive) {
                Log.i("BootReceiver", "Device booted with active compliance flag - no alarm rescheduling needed")
            }

        } catch (e: Exception) {
            Log.e("BootReceiver", "Error during boot/time reschedule", e)
        }
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
