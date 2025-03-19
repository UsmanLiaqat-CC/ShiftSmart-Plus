package com.shiftsmart.plus.periodicAction

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.common.util.concurrent.ListenableFuture
import com.shiftsmart.plus.R
import com.shiftsmart.plus.database.DbConstants.RECORD_INTERVAL
import com.shiftsmart.plus.services.LocationTrack
import com.shiftsmart.plus.services.MyService
import com.shiftsmart.plus.utils.SharedPref
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class ApiWorker(context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        try {
            val user = SharedPref.getInstance(applicationContext)?.getUser()

            user?.let {
                Log.i("ApiWorker", "User Info: $user")

                if (user.isActive == true) {
                    Log.i("ApiWorker", "API Worker Triggered")

//                    // ✅ Check if another work is already scheduled
//                    if (!isWorkAlreadyScheduled()) {
//                        Log.i("ApiWorker", "Scheduling next API Worker execution")
//
//                        val oneTimeRequest = OneTimeWorkRequestBuilder<ApiWorker>()
//                            .setInitialDelay(RECORD_INTERVAL.toLong(), TimeUnit.MINUTES) // Delay as needed
//                            .build()
//
//                        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
//                            "API_WORK",
//                            ExistingWorkPolicy.REPLACE, // ✅ This ensures that every trigger request schedules the API worker immediately.
//                            oneTimeRequest
//                        )
//                    } else {
//                        Log.i("ApiWorker", "WorkManager task is already scheduled. Skipping duplicate.")
//                    }
                    Log.i("ApiWorker", "Service not running. Starting now.")
                    val serviceIntent = Intent(applicationContext, MyService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        applicationContext.startForegroundService(serviceIntent)
                    } else {
                        applicationContext.startService(serviceIntent)
                    }
                }
            }
            return Result.success()

        } catch (e: Exception) {
            Log.e("ApiWorker", "Error in API Worker", e)
            return Result.failure()
        }
    }


    private val executor: Executor = Executors.newSingleThreadExecutor()

    private suspend fun isWorkAlreadyScheduled(): Boolean {
        val workManager = WorkManager.getInstance(applicationContext)
        val future: ListenableFuture<List<WorkInfo>> = workManager.getWorkInfosForUniqueWork("API_WORK")

        return suspendCancellableCoroutine { continuation ->
            future.addListener({
                val workInfos = future.get() // Get the result
                val isScheduled = workInfos.any {
                    it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING
                }
                continuation.resume(isScheduled)
            }, executor)
        }
    }
}

