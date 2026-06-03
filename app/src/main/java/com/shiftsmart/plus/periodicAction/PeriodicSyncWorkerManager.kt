package com.shiftsmart.plus.periodicAction

import android.content.Context
import android.util.Log
import androidx.work.*
import androidx.work.NetworkType
import com.shiftsmart.plus.utils.DeviceCompatibilityHelper
import java.util.concurrent.TimeUnit

/**
 * PeriodicSyncWorkerManager
 *
 * PURPOSE: Manages WorkManager-based periodic sync as FALLBACK for AlarmManager
 *
 * CRITICAL FOR ANDROID 10 RELIABILITY:
 * - Ensures sync continues even when AlarmManager gets throttled by Doze
 * - WorkManager survives app standby and aggressive battery optimization
 * - Provides redundancy without interfering with AlarmManager
 *
 * SYNC STRATEGY:
 * - Runs every 15 minutes (WorkManager minimum for periodic work)
 * - Worker validates 5-minute boundaries internally
 * - Only syncs when conditions are met (inside shift, on boundary, valid gap)
 * - Re-arms AlarmManager if needed
 *
 * CALL THIS:
 * - On app launch (MainActivity onCreate)
 * - After login
 * - When user updates timetable
 * - On boot (BootReceiver)
 */
object PeriodicSyncWorkerManager {
    private const val TAG = "PeriodicSyncWorkerMgr"
    private const val WORK_NAME = "periodic_sync_work"

    /**
     * Start periodic sync worker
     * This provides redundancy for AlarmManager
     */
    fun startPeriodicSync(context: Context) {
        Log.i(TAG, "🔄 Starting periodic sync worker (15-min intervals)")

        // Check if device is problematic (Mara/Mobicel)
        val isProblematic = DeviceCompatibilityHelper.isProblematicDevice()
        if (isProblematic) {
            Log.w(TAG, "⚠️ Problematic device detected - using aggressive worker strategy")
        }

        // Use more relaxed constraints for problematic devices
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (isProblematic) NetworkType.NOT_REQUIRED // Mara/Mobicel: any network
                else NetworkType.NOT_REQUIRED
            )
            .setRequiresBatteryNotLow(false) // Run even on low battery
            .setRequiresCharging(false) // Run even when not charging
            .setRequiresStorageNotLow(false) // Don't wait for storage
            .build()

        val periodicWorkRequest = PeriodicWorkRequestBuilder<PeriodicSyncWorker>(
            15, // Minimum interval for PeriodicWorkRequest
            TimeUnit.MINUTES,
            5, // Flex interval - allows execution within last 5 minutes of period
            TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.LINEAR,
                if (isProblematic) 30L else 1L, // Retry faster on problematic devices
                if (isProblematic) TimeUnit.SECONDS else TimeUnit.MINUTES
            )
            .addTag("periodic_sync")
            .addTag(if (isProblematic) "problematic_device" else "normal_device")
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP, // Don't replace if already running
            periodicWorkRequest
        )

        Log.i(TAG, "✅ Periodic sync worker enqueued successfully")

        // For problematic devices, also schedule immediate one-time work
        if (isProblematic) {
            Log.i(TAG, "⚡ Scheduling immediate sync for problematic device")
            triggerImmediateSync(context)
        }
    }


    /**
     * Force immediate sync (one-time work)
     * Useful for testing or manual triggers
     */
    fun triggerImmediateSync(context: Context) {
        Log.i(TAG, "⚡ Triggering immediate sync")

        val oneTimeWorkRequest = OneTimeWorkRequestBuilder<PeriodicSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .build()
            )
            .addTag("immediate_sync")
            .build()

        WorkManager.getInstance(context).enqueue(oneTimeWorkRequest)
    }

    /**
     * Stop all periodic/immediate sync work.
     * Use on local logout/session expiry.
     */
    fun stopPeriodicSync(context: Context) {
        Log.i(TAG, "🛑 Stopping periodic sync workers")
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        WorkManager.getInstance(context).cancelAllWorkByTag("periodic_sync")
        WorkManager.getInstance(context).cancelAllWorkByTag("immediate_sync")
    }
}
