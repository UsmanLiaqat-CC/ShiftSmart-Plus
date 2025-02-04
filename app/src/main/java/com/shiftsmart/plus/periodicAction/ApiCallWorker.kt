package com.shiftsmart.plus.periodicAction

import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

class ApiCallWorker @Inject constructor(
    @ApplicationContext private val context: Context,
    workerParams: WorkerParameters,
    private val repository: MainRepository,
    private val db: ShiftSmartPlusDatabase,
    private val track: LocationTrack,
) : CoroutineWorker(context, workerParams) {

    private val dao: DBDao = db.dbDao()

    override suspend fun doWork(): Result {
        Log.i(TAG, "doWork: ")
        return try {
            callApiData()
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error in worker: ${e.message}")
            Result.retry()
        }
    }

    private suspend fun callApiData() {

        if (track.checkLocationPermissions())
        {
            val locationTrack = LocationTrack(applicationContext)
            val mLocationManager = applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

            locationTrack.getLocation(mLocationManager) { location ->
                // Check if location is not null
                location?.let {
                    CoroutineScope(Dispatchers.IO).launch {
                        callApi(it.latitude, it.longitude)
                    }
                    Log.i(TAG, "fetchLocationData: location not null: $it")
                }
            }
        }else{
            callApi(lat = 0.0, lan = 0.0)
        }
    }

    private suspend fun callApi(lat: Double, lan: Double) {
        val wifiScanner = WifiScanner(context)
        var wifiList= listOf<WifiModel>()
         wifiScanner.scanWifiNetworks {  scanResults ->


            wifiList = if (scanResults.isNotEmpty()) {
                scanResults.map { result ->
                    WifiModel(ssid = result.SSID, bssid = result.BSSID, strength = Utils.rssiToPercentage(result.level) )
                }
            } else{
                arrayListOf()
            }
             val user = SharedPref.getInstance(context)?.getUser()
             val record = RecordModel(
                 uuid = Utils.generateRandomFourDigitUuid(),
                 user_id = user?.id.toString(),
                 lat = lat,
                 lng = lan,
                 localTime = Utils.getCurrent24HourTime(),
                 time = Utils.getCurrentUtcTime(),
                 attendanceType = StatusEnum.default.name,
                 attendanceStatus = Utils.checkInternetAndSetStatus(context),
                 isForceAttendance = false,
                 isLocation = track.checkLocationPermissions(),
                 wifiService = wifiScanner.isWifiEnabled(),
                 dataService = Utils.isMobileDataEnabled(context),
                 notification = Utils.isNotificationPermissionGranted(context),
                 batterySaver = Utils.isBatterySaverOn(context),
                 batteryOptimization = Utils.isBatteryOptimizationOff(context),
                 wifi_list = wifiList
             )

             if (Utils.isInternetAvailable(context)) {
                 val records = dao.getAllRecords(user?.id.toString()).map { it.toDataRequest() }.toMutableList()
                 records.add(record.toDataRequest())

                 val token = SharedPref.getInstance(context)?.getToken() ?: ""
                 CoroutineScope(Dispatchers.IO).launch {
                     val response = callServerApi(records, token)

                     if (response.isSuccessful) {
                         handleSuccessfulResponse(record, response.body())
                         sendNotificationUpdate("Data synced to admin panel at ${Utils.getCurrentDateTime()}")

                     } else {
                         handleUnsuccessfulResponse(response)
                     }
                 }

             } else {
                 CoroutineScope(Dispatchers.IO).launch {
                     dao.insertRecord(record)
                     sendNotificationUpdate("Data saved in database as ${Utils.getCurrentDateTime()}")
                 }
             }
        }


    }
    fun RecordModel.toDataRequest(): DataRequest {
        return DataRequest(
            UUID = this.uuid,
            user_id = this.user_id,
            lat = this.lat,
            lng = this.lng,
            localTime = this.localTime,
            time = this.time,
            attendanceType = this.attendanceType,
            attendanceStatus = this.attendanceStatus,
            isForceAttendance = this.isForceAttendance,
            isLocation = this.isLocation,
            wifiService = this.wifiService,
            dataService = this.dataService,
            notification = this.notification,
            batterySaver = this.batterySaver,
            batteryOptimization = this.batteryOptimization,
            wifi_list = this.wifi_list
        )
    }


    private suspend fun callServerApi(data: List<DataRequest>, token: String) = repository.sendData(data, token)

    private suspend fun handleSuccessfulResponse(record: RecordModel, response: AttendaceResponseModel?) {
        response?.data?.forEach { attendance ->
            if (attendance.attendanceStatus == "offline") {
                dao.deleteRecordByUuid(attendance.UUID)
            }
        }
    }

    private suspend fun handleUnsuccessfulResponse(response: retrofit2.Response<AttendaceResponseModel>) {
        response.errorBody()?.let {
            val errorResponse = response.parseErrorBody()
            errorResponse?.errors?.firstOrNull()?.let { error ->
                if (error.detail == "LOGOUT" || error.code in listOf(401, 422)) {
                    withContext(Dispatchers.IO) {
                        dao.deleteAllRecords()
                        SharedPref.getInstance(context)?.clearPrefrence()
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "ApiCallWorker"
    }
    private fun sendNotificationUpdate(message: String) {
        val intent = Intent("UPDATE_NOTIFICATION")
        intent.putExtra("message", message)
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
    }
}