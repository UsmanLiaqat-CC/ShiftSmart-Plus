package com.shiftsmart.plus.utils
import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.work.Configuration
import com.shiftsmart.plus.R

import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

import androidx.work.WorkManager


@HiltAndroidApp
class MyApp : Application(), Configuration.Provider {

    companion object {
        var isInForeground: Boolean = false
            private set
    }

    private var activityReferences = 0
    private var isActivityChangingConfigurations = false

    override fun onCreate() {
        super.onCreate()

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {
                activityReferences++
                if (!isActivityChangingConfigurations) {
                    isInForeground = true
                }
            }
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {
                isActivityChangingConfigurations = activity.isChangingConfigurations
                activityReferences--
                if (activityReferences == 0 && !isActivityChangingConfigurations) {
                    isInForeground = false
                }
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        // Initialize WorkManager
        WorkManager.initialize(this, workManagerConfiguration)

    }

    override val workManagerConfiguration: Configuration
        get() =
             Configuration.Builder()
                .setMinimumLoggingLevel(android.util.Log.INFO) // Optional: Set minimum logging level for WorkManager
                .build()

}


