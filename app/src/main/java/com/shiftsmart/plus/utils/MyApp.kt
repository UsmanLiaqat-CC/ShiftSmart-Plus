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
import com.shiftsmart.plus.database.RecordModel
import com.shiftsmart.plus.models.TimeRange
import com.shiftsmart.plus.services.MyService
import com.shiftsmart.plus.utils.Utils.getCalendarForShift
import com.shiftsmart.plus.utils.Utils.getCurrentDayName
import java.util.Calendar


@HiltAndroidApp
class MyApp : Application(), Configuration.Provider {

    override fun onCreate() {
        super.onCreate()

        // Initialize WorkManager
        WorkManager.initialize(this, workManagerConfiguration)

    }

    override val workManagerConfiguration: Configuration
        get() =
             Configuration.Builder()
                .setMinimumLoggingLevel(android.util.Log.INFO) // Optional: Set minimum logging level for WorkManager
                .build()

}


