package com.shiftsmart.plus.ui.activities

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.findNavController
import com.shiftsmart.plus.R
import com.shiftsmart.plus.periodicAction.AlarmScheduler
import com.shiftsmart.plus.utils.SharedPref
import dagger.hilt.android.AndroidEntryPoint

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
            if (it?.isActive == true) {
                // use here workmanager
                // 🔹 Schedule WorkManager for periodic API calls
                it.timetable?.range?.let { it1 ->
                    AlarmScheduler.scheduleAlarms(this,
                        it1
                    )
                }
            }
        }
    }
}