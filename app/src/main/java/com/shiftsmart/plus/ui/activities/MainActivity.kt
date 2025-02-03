package com.shiftsmart.plus.ui.activities

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.shiftsmart.plus.R
import com.shiftsmart.plus.service.MyForegroundService
import com.shiftsmart.plus.service.scheduleDailyService
import com.shiftsmart.plus.utils.SharedPref
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.TimeUnit

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)



    }

    override fun onResume() {
        super.onResume()
        val user=SharedPref.getInstance(this)?.getUser()

        user?.let {
//            scheduleWorker()
            scheduleDailyService(this)
        }
    }
}