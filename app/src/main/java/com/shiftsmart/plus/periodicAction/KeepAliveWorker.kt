package com.shiftsmart.plus.periodicAction

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.shiftsmart.plus.services.MyService
import java.util.concurrent.TimeUnit

class KeepAliveWorker(context: Context, workerParams: WorkerParameters) : Worker(context, workerParams) {
    override fun doWork(): Result {
        // Start/ping your service
        val intent = Intent(applicationContext, MyService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.startForegroundService(intent)
        } else {
            applicationContext.startService(intent)
        }
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<KeepAliveWorker>(
                15, TimeUnit.MINUTES, // Run every 15 minutes
                5, TimeUnit.MINUTES   // Flexible window
            ).setConstraints(constraints)
             .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "KeepAliveWorker",
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
        }
    }
}