package com.shiftsmart.plus.periodicAction

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
        var action = intent.action ?: return
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

        // ⚠️ Before user unlock, credential-protected storage (your SharedPref) may be unavailable.
//        //    If you don't use device-protected storage, wait for unlock.
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
//            val um = appContext.getSystemService(UserManager::class.java)
//            if (um != null && !um.isUserUnlocked) {
//                Log.w("BootReceiver", "User not unlocked yet. Deferring reschedule.")
//                return
//            }
//        }

        try {
            val user = SharedPref.getInstance(appContext)?.getUser()
            Log.i("BootReceiver", "Retrieved user from SharedPref: $user")

            if (user != null && AlarmReceiver.isInsideShiftWindow(user)) {
                Log.i(
                    "BootReceiver",
                    "⏰ Service destroyed during shift - AlarmManager will handle next wake-up"
                )
                // Ensure alarms are scheduled (they should already be, but just in case)
                AlarmReceiver.scheduleNextAlignedAlarm(context)
            } else {
                Log.i("BootReceiver", "⏸️ Service destroyed outside shift - no action needed")
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
