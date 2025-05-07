package com.shiftsmart.plus.utils

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.maps.model.LatLng
import com.shiftsmart.plus.database.DBDao
import com.shiftsmart.plus.database.RecordModel
import com.shiftsmart.plus.enums.StatusEnum
import com.shiftsmart.plus.models.AttendaceResponseModel
import com.shiftsmart.plus.models.DataRequest
import com.shiftsmart.plus.models.TimeRange
import com.shiftsmart.plus.models.UserModel
import com.shiftsmart.plus.models.WifiModel
import com.shiftsmart.plus.repository.MainRepository
import com.shiftsmart.plus.utils.Utils.getCalendarForShift
import com.shiftsmart.plus.utils.Utils.getCurrentDayName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Response
import java.time.Duration
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Calendar
import javax.inject.Inject

class AttendanceSyncManager @Inject constructor(
    private val context: Context,
    private val repository: MainRepository,
    private val dao: DBDao,
    private val locationHelper: LocationHelper,
    private val wifiManager: WifiManager
)
{
//    private var lastApiCallTime = 0L
    private var apiCallInProgress = false
    private val checkInterval = 5 * 60 * 1000L // 5 minutes, can be made configurable
    private var lastLocation: LatLng = LatLng(0.0, 0.0)
    private val wifiScanResults = mutableListOf<WifiModel>()
    // Servi
    private  val TAG = "AttendanceSyncManager"
    suspend fun startSyncProcess() {
        locationHelper.fetchFreshLocation()?.let {
            lastLocation = it
        }

        maybeTriggerApiCall()
    }

    private fun maybeTriggerApiCall() {
        val now = SystemClock.elapsedRealtime()
        val lastApiCallTime = SharedPref.getInstance(context)?.getLastApiCallTime() ?: 0L
        val lastMinutes = lastApiCallTime / 1000 / 60
        val lastSeconds = (lastApiCallTime / 1000) % 60
        Log.i(TAG, "maybeTriggerApiCall: lastApiCallTime = $lastApiCallTime (${lastMinutes}m ${lastSeconds}s)")
        
        if (lastApiCallTime == 0L ) {
            Log.d(TAG, "if performing api call at:${Utils.getCurrentDateTime()}")
          
            performApiCall()
        } else {

            val elapsedMillis = now - lastApiCallTime
            val elapsedMinutes = elapsedMillis / 1000 / 60
            val elapsedSeconds = (elapsedMillis / 1000) % 60
            val checkIntervalMinutes = checkInterval / 1000 / 60

            Log.d(TAG, "maybeTriggerApiCall: Time since last call = ${elapsedMinutes}m ${elapsedSeconds}s (Check interval: ${checkIntervalMinutes}m)")

            if (elapsedMillis >= checkInterval) {
                Log.d(TAG, "maybeTriggerApiCall: Enough time passed, calling performApiCall()")
                performApiCall()
            } else {
                val waitMillis = checkInterval - elapsedMillis
                val waitMinutes = waitMillis / 1000 / 60
                val waitSeconds = (waitMillis / 1000) % 60

                sendNotificationUpdate("Next Data Sync after ${waitMinutes}m ${waitSeconds}s")
            }
            // Skipped due to interval
        }
    }

    private fun performApiCall() {
        Log.i(TAG, "performApiCall: at:${Utils.getCurrentDateTime()}")

        if (apiCallInProgress) return

        apiCallInProgress = true
        SharedPref.getInstance(context)?.saveLastApiCallTime( SystemClock.elapsedRealtime())  // Save the current time
     
        val user = SharedPref.getInstance(context)?.getUser()
        user?.let {
            val record = createRecord(it)
            CoroutineScope(Dispatchers.IO).launch {
                it.timetable?.range?.let { saveDataLocally(record, it,user) }
            }
        }
    }

    private fun createRecord(user: UserModel): RecordModel {
        return RecordModel(
            uuid = Utils.generateRandomFourDigitUuid(),
            user_id = user._id.toString(),
            lat = lastLocation.latitude,
            lng = lastLocation.longitude,
            localTime = Utils.getCurrent24HourTime(),
            time = Utils.getCurrentUtcTime(),
            attendanceType = StatusEnum.default.name,
            attendanceStatus = Utils.checkInternetAndSetStatus(context),
            isForceAttendance = false,
            isLocation = checkLocationPermissions(),
            wifiService = wifiManager.isWifiEnabled,
            dataService = Utils.isMobileDataEnabled(context),
            notification = Utils.isNotificationPermissionGranted(context),
            batterySaver = !Utils.isBatterySaverOn(context),
            batteryOptimization = !Utils.isBatteryOptimizationOff(context),
            wifi_list = wifiScanResults
        )
    }
    fun checkLocationPermissions(): Boolean {
        val fineLocationPermission = ActivityCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        )
        val coarseLocationPermission = ActivityCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        )

        // Check for Android 14+ (if needed)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            fineLocationPermission == PackageManager.PERMISSION_GRANTED &&
                    coarseLocationPermission == PackageManager.PERMISSION_GRANTED
        } else {
            // For Android versions below 13 (including 13 itself)
            fineLocationPermission == PackageManager.PERMISSION_GRANTED &&
                    coarseLocationPermission == PackageManager.PERMISSION_GRANTED
        }

    }
    private suspend fun callApi(record: RecordModel, user: UserModel) {
        Log.i(TAG, "Calling API with record: ${record}\n at:${Utils.getCurrentDateTime()}")
        val records = dao.getAllRecords(user._id.toString()).map { it.toDataRequest() }.toMutableList()
//        records.add(record.toDataRequest())

        if (Utils.isInternetAvailable(context)) {
            val token = SharedPref.getInstance(context)?.getToken() ?: ""
            try {
                val response = repository.sendData(records, token)
                if (response.isSuccessful) {
                    apiCallInProgress = false
                    Log.i(TAG, "API call successful, handling response.")
                    handleSuccessfulResponse(response.body(), record)
                    Log.i(TAG, "MRcallApi: record successfully sent to admin panal")
                    sendNotificationUpdate("Data synced to admin panel at ${Utils.getCurrentDateTime()}")
                } else {
                    Log.e(TAG, "API call failed: ${response.errorBody()}")
                    handleUnsuccessfulResponse(response)
                }
            } catch (e: Exception) {
                Log.e(TAG, "API call exception: ${e.message}")
                apiCallInProgress = false
            }
        } else {
            Log.e(TAG, "No internet available for API call.")
            apiCallInProgress = false
        }
    }

    private suspend fun handleSuccessfulResponse(
        response: AttendaceResponseModel?,
        record: RecordModel
    ) {
        response?.data?.forEach { attendance ->
            // Handle successful response, maybe clean up the records locally if successful
            dao.deleteRecordByUuid(attendance.UUID)
        }
        Log.i(TAG, "API data successfully processed and cleaned.")
    }

    private suspend fun handleUnsuccessfulResponse(response: Response<AttendaceResponseModel>) {
        response.errorBody()?.let {
            val errorResponse = response.parseErrorBody()
            Log.i(TAG, "Error response: $errorResponse")
            errorResponse?.errors?.firstOrNull()?.let { error ->
                if (error.detail == "LOGOUT" || error.code in listOf(401, 422, 500)) {
                    withContext(Dispatchers.IO) {
                        // Clear user data if required
                        SharedPref.getInstance(context)?.clearPrefrence()
                    }
                }
            }
        }
        apiCallInProgress = false
    }

    private fun saveDataLocally(record: RecordModel, shifts: List<TimeRange>, user: UserModel) {
        Log.i(TAG, "Saving data locally for record: ${record}")
        val today = getCurrentDayName() // Get today's name (e.g., "Tuesday")

        val todayShift = shifts.find { it.day.equals(today, ignoreCase = true) }

        if (todayShift != null && todayShift.start != null && todayShift.end != null) {
            Log.i(TAG, "Today's Shift -> day:${todayShift.day}, start:${todayShift.start}, end:${todayShift.end}")

            val startCalendar = getCalendarForShift(todayShift.day, todayShift.start, -1)
            val endCalendar = getCalendarForShift(todayShift.day, todayShift.end, 1)

            val currentTime = Calendar.getInstance()

            if (startCalendar != null && endCalendar != null) {
                // Schedule API Worker ONLY IF current time is between shift start & end
                if (currentTime.after(startCalendar) && currentTime.before(endCalendar)) {
                    val latest = dao.getLatestRecord(record.user_id)
                    if (shouldInsertRecord(latest, record)) {
                        dao.insertRecord(record)
                        sendNotificationUpdate("Data stored at ${Utils.getCurrentDateTime()}")
                        CoroutineScope(Dispatchers.IO).launch {
                            callApi(record,user)
                        }
                    } else {
                        Log.d("DBDao", "Record not inserted: Time difference <= 5 minutes")
                    }
                } else {
                    val intent = Intent("com.shiftsmart.plus.ACTION_FINISH")
                    context.sendBroadcast(intent)
                    Log.i(TAG, "Current time is outside shift period, NOT scheduling API Worker.")
                }
            }
        } else {
            Log.i(TAG, "No shift found for today.")
            val intent = Intent("com.shiftsmart.plus.ACTION_FINISH")
            context.sendBroadcast(intent)
        }
    }
    // Don't forget to unbind when done

    private fun sendNotificationUpdate(message: String) {
        Log.i(TAG, "sendNotificationUpdate: on${Utils.getCurrentDateTime()}-->message:${message}")
        val intent = Intent("UPDATE_NOTIFICATION")
        intent.putExtra("message", message)
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
    }
    private fun shouldInsertRecord(
        latestRecord: RecordModel?,
        newRecord: RecordModel
    ): Boolean {
        if (latestRecord == null) return true // No record yet, so insert

        val formatter = DateTimeFormatter.ofPattern("HH:mm")
        val latestTime = LocalTime.parse(latestRecord.localTime, formatter)
        val newTime = LocalTime.parse(newRecord.localTime, formatter)

        val duration = Duration.between(latestTime, newTime).toMinutes()

        return duration >= 5
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

    fun setWifiList(wifiList: MutableList<WifiModel>) {
        wifiScanResults.clear()
        wifiScanResults.addAll(wifiList);
    }

}
