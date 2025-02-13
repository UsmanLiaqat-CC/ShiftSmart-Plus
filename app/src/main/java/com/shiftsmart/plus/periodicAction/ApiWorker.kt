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
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.shiftsmart.plus.R
import com.shiftsmart.plus.database.DbConstants.RECORD_INTERVAL
import com.shiftsmart.plus.services.LocationTrack
import com.shiftsmart.plus.services.MyService
import com.shiftsmart.plus.utils.SharedPref
import java.util.concurrent.TimeUnit

class ApiWorker(context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {

        try {

            val user= SharedPref.getInstance(context = applicationContext)?.getUser()
            user?.let { user1 ->
                Log.i("ApiWorker", "onReceive: user1:${user1}")
                if (user1?.isActive == true) {
                    Log.i("ApiWorker", "API Worker Triggered")
                    val oneTimeRequest = OneTimeWorkRequestBuilder<ApiWorker>()
                        .setInitialDelay(RECORD_INTERVAL.toLong(), TimeUnit.MINUTES) // Customize the delay as needed
                        .build()

                    WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                        "API_WORK",
                        ExistingWorkPolicy.REPLACE, // Replaces any existing work with the same name
                        oneTimeRequest
                    )

                    if (!isServiceRunning(MyService::class.java)) {

                        Log.i("ApiWorker", "Service not running. Starting now.")
                        val serviceIntent = Intent(applicationContext, MyService::class.java)

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            applicationContext.startForegroundService(serviceIntent) // 🔹 Use startForegroundService for Android 8+
                        } else {
                            applicationContext.startService(serviceIntent) // 🔹 For lower versions
                        }
                    }
                    else {
                        Log.i("ApiWorker", "Service running. Sending update.")
                        val updateIntent = Intent("UPDATE_NOTIFICATION").apply {
                            putExtra("message", "Triggering API call via WorkManager")
                        }
                        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(updateIntent)
                    }
                }
            }



            return Result.success()
        } catch (e: Exception) {
            Log.e("ApiWorker", "Error in API Worker", e)
            return Result.failure()
        }
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.getRunningServices(Int.MAX_VALUE).any { it.service.className == serviceClass.name }
    }

}
