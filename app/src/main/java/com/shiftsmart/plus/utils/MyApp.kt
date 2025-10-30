package com.shiftsmart.plus.utils
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.Configuration
import com.shiftsmart.plus.R

import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

import androidx.work.WorkManager


@HiltAndroidApp
class MyApp : Application(), Configuration.Provider {

    override fun onCreate() {
        super.onCreate()


        // Start minute-by-minute monitoring
        MinuteMonitorHelper.startMonitoring(this)
        // Initialize WorkManager
        WorkManager.initialize(this, workManagerConfiguration)

    }

    override val workManagerConfiguration: Configuration
        get() =
             Configuration.Builder()
                .setMinimumLoggingLevel(android.util.Log.INFO) // Optional: Set minimum logging level for WorkManager
                .build()

}


