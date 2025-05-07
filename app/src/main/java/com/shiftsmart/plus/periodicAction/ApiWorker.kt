package com.shiftsmart.plus.periodicAction

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.shiftsmart.plus.utils.Utils

// New Worker class for WorkManager backup
class ApiWorker(context: Context, workerParams: WorkerParameters) : Worker(context, workerParams) {
    override fun doWork(): Result {
        Log.i("TAG", "PrintData: ApiWorker: at ${Utils.getCurrentDateTime()} ")


        return Result.success()
    }
}
