package com.shiftsmart.plus.periodicAction

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.shiftsmart.plus.database.DBDao
import com.shiftsmart.plus.database.RecordModel
import com.shiftsmart.plus.database.ShiftSmartPlusDatabase
import com.shiftsmart.plus.enums.StatusEnum
import com.shiftsmart.plus.models.AttendaceResponseModel
import com.shiftsmart.plus.models.DataRequest
import com.shiftsmart.plus.models.WifiModel
import com.shiftsmart.plus.repository.MainRepository
import com.shiftsmart.plus.utils.LocationTrack
import com.shiftsmart.plus.utils.SharedPref
import com.shiftsmart.plus.utils.Utils
import com.shiftsmart.plus.utils.WifiScanner
import com.shiftsmart.plus.utils.parseErrorBody
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

class AlarmReceiver : BroadcastReceiver() {


    private  val TAG = "AlarmReceiver"
    
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context != null) {

            Log.d("AlarmReceiver", "Triggering API call via WorkManager")
//
            val workRequest = OneTimeWorkRequestBuilder<ApiCallWorker>()
                .addTag("API_WORK")
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "API_CALL_WORK",
                ExistingWorkPolicy.REPLACE, // Prevent duplicate work
                workRequest
            )

//            WorkManager.getInstance(context).enqueue(workRequest)

            // 🔹 Reschedule for the next 5 minutes
            AlarmScheduler.scheduleApiWorker(context)

        }
    }

}
