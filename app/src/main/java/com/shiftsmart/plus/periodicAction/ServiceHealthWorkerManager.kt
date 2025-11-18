package com.shiftsmart.plus.periodicAction

import android.content.Context
import android.util.Log
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * Manages the 15-minute periodic health check worker
 *
 * PURPOSE:
 * ════════
 * Ensures MyService stays running during shift hours by scheduling
 * a WorkManager job that runs every 15 minutes to verify service health
 * and restart it if needed.
 *
 * USAGE:
 * ══════
 * Call ServiceHealthWorkerManager.schedulePeriodicHealthCheck(context)
 * - On app launch
 * - When user logs in
 * - After service starts
 *
 * WHY WORKMANAGER:
 * ═══════════════
 * - Survives Doze mode better than AlarmManager
 * - Guaranteed execution by Android system
 * - Battery-efficient with flex intervals
 * - Persists across device reboots
 */
object ServiceHealthWorkerManager {

    private const val TAG = "ServiceHealthWorkerManager"
    private const val WORK_NAME = "service_health_check_periodic"
    private const val REPEAT_INTERVAL_MINUTES = 15L
    private const val FLEX_INTERVAL_MINUTES = 5L // Allow system to run within 5-min window

    /**
     * Schedule a periodic worker that runs every 15 minutes to check service health.
     *
     * CONSTRAINTS:
     * ════════════
     * - No network required (works offline)
     * - No battery not low requirement (runs even on low battery)
     * - No charging requirement (runs on battery)
     *
     * FLEX INTERVAL:
     * ═════════════
     * Allows Android to batch work for battery efficiency.
     * Worker will run between minute 10-15 of each interval.
     *
     * @param context Application context
     */
    fun schedulePeriodicHealthCheck(context: Context) {
        try {
            // Create constraints - minimal restrictions for reliability
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED) // Works offline
                .setRequiresBatteryNotLow(false) // Run even on low battery
                .setRequiresCharging(false) // Run on battery
                .build()

            // Create periodic work request with flex interval
            val periodicWorkRequest = PeriodicWorkRequestBuilder<ServiceHealthWorker>(
                REPEAT_INTERVAL_MINUTES,
                TimeUnit.MINUTES,
                FLEX_INTERVAL_MINUTES, // Flex: can run between 10-15 min mark
                TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .addTag(WORK_NAME)
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            // Schedule with KEEP policy to avoid recreating if already exists
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP, // Keep existing if already scheduled
                    periodicWorkRequest
                )

            Log.i(TAG, "✅ Periodic health check scheduled (every 15 minutes)")
            logWorkStatus(context)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error scheduling periodic health check", e)
        }
    }

    /**
     * Cancel the periodic health check worker.
     * Call this on logout or when service should not run.
     */
    fun cancelPeriodicHealthCheck(context: Context) {
        try {
            WorkManager.getInstance(context)
                .cancelUniqueWork(WORK_NAME)

            Log.i(TAG, "✅ Periodic health check cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error cancelling periodic health check", e)
        }
    }

    /**
     * Check if the periodic worker is currently scheduled and running
     */
    fun isHealthCheckScheduled(context: Context): Boolean {
        return try {
            val workInfos = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(WORK_NAME)
                .get()

            val isScheduled = workInfos.any {
                it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING
            }

            Log.i(TAG, "Health check scheduled: $isScheduled")
            isScheduled
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error checking worker status", e)
            false
        }
    }

    /**
     * Force run the health check immediately (for testing)
     */
    fun runHealthCheckNow(context: Context) {
        try {
            val oneTimeRequest = OneTimeWorkRequestBuilder<ServiceHealthWorker>()
                .addTag("${WORK_NAME}_manual")
                .build()

            WorkManager.getInstance(context)
                .enqueue(oneTimeRequest)

            Log.i(TAG, "✅ Manual health check triggered")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error running manual health check", e)
        }
    }

    /**
     * Log current status of the periodic worker (for debugging)
     */
    private fun logWorkStatus(context: Context) {
        try {
            val workInfos = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(WORK_NAME)
                .get()

            if (workInfos.isEmpty()) {
                Log.i(TAG, "📊 Worker Status: Not scheduled")
            } else {
                workInfos.forEach { workInfo ->
                    Log.i(TAG, "📊 Worker Status: ${workInfo.state}")
                    Log.i(TAG, "   Next run: ${workInfo.nextScheduleTimeMillis}")
                    Log.i(TAG, "   Run attempt: ${workInfo.runAttemptCount}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error getting work status", e)
        }
    }

    /**
     * Get time until next scheduled run (for UI display)
     */
    fun getNextRunTimeMillis(context: Context): Long {
        return try {
            val workInfos = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(WORK_NAME)
                .get()

            workInfos.firstOrNull()?.nextScheduleTimeMillis ?: 0L
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error getting next run time", e)
            0L
        }
    }
}

