package com.shiftsmart.plus.utils

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.shiftsmart.plus.periodicAction.AlarmScheduler
import com.shiftsmart.plus.periodicAction.PeriodicSyncWorkerManager
import com.shiftsmart.plus.periodicAction.RestartWatchdogManager
import com.shiftsmart.plus.periodicAction.ServiceHealthWorkerManager
import com.shiftsmart.plus.periodicAction.ShiftRestartAlarmManager
import com.shiftsmart.plus.services.MyService
import com.shiftsmart.plus.utils.Utils.isServiceRunning

/**
 * Logs the user out on this phone without needing the UI.
 * Used for server logout (404 LOGOUT, 401, 403), the force logout push and the Logout button.
 * Safe to call more than once.
 */
object SessionCleanup {

    private const val TAG = "SessionCleanup"

    fun logoutLocally(context: Context) {
        val appContext = context.applicationContext
        val sharedPref = SharedPref.getInstance(appContext)

        try {
            sharedPref?.getUser()?._id?.let { userId ->
                FirebaseMessaging.getInstance().unsubscribeFromTopic(userId)
            }
            sharedPref?.saveSubscribedFcmUserId("")

            // Stop everything that could start another upload
            AlarmScheduler.cancelAllAlarms(appContext)
            AlarmScheduler.cancelComplaintAlarm(appContext)
            PeriodicSyncWorkerManager.stopPeriodicSync(appContext)
            ServiceHealthWorkerManager.cancelPeriodicHealthCheck(appContext)
            ShiftRestartAlarmManager.cancelAllRestartSchedules(appContext)
            RestartWatchdogManager.cancelOneMinuteRestart(appContext)

            (appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancelAll()

            if (isServiceRunning(appContext, MyService::class.java)) {
                appContext.stopService(Intent(appContext, MyService::class.java))
            }

            sharedPref?.saveIsComplaintActive(false)
            FingerprintHelper.setFingerprintEnabled(appContext, false)
            sharedPref?.clearLastSyncTime()
            sharedPref?.clearPrefrence()
            Log.i(TAG, "Logged out locally")
        } catch (e: Exception) {
            Log.e(TAG, "Local logout failed: ${e.message}", e)
        }
    }
}
