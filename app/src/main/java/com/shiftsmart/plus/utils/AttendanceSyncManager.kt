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
import com.shiftsmart.plus.database.IssueModel
import com.shiftsmart.plus.database.RecordModel
import com.shiftsmart.plus.enums.StatusEnum
import com.shiftsmart.plus.models.AttendaceResponseModel
import com.shiftsmart.plus.models.DataRequest
import com.shiftsmart.plus.models.ErrorModel
import com.shiftsmart.plus.models.TimeRange
import com.shiftsmart.plus.models.UserModel
import com.shiftsmart.plus.models.WifiModel
import com.shiftsmart.plus.repository.MainRepository
import com.shiftsmart.plus.utils.Utils.getCalendarForShift
import com.shiftsmart.plus.utils.Utils.getCurrentDayName
import com.shiftsmart.plus.utils.Utils.toLocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Response
import java.time.Duration
import java.time.LocalDate
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
    private var apiCallInProgress = false
    private var lastLocation: LatLng = LatLng(0.0, 0.0)
    private val wifiScanResults = mutableListOf<WifiModel>()
    private  val TAG = "AttendanceSyncManager"
    private val managerJob = SupervisorJob()
    private val managerScope = CoroutineScope(Dispatchers.IO + managerJob)

    suspend fun startSyncProcess() {
        if (!locationHelper.hasLocationPermissions()) {
            Log.w(TAG, "startSyncProcess: Location permissions not granted, skipping location fetch.")
           lastLocation= locationHelper.lastLocation
            performApiCall()
            return
        }

        try {
            val location = locationHelper.fetchFreshLocation()
            lastLocation = location
            Log.d(TAG, "startSyncProcess: Location fetched: $lastLocation")
        } catch (e: Exception) {
            Log.e(TAG, "startSyncProcess: Failed to fetch location", e)
        }

        performApiCall()
    }



    private fun performApiCall() {
        Log.i(TAG, "performApiCall: at:${Utils.getCurrentDateTime()}-->isApiCallInProgress:${apiCallInProgress}")
        if (apiCallInProgress) return
        apiCallInProgress = true

        val user = SharedPref.getInstance(context)?.getUser()
        user?.let { u ->
            val record = createRecord(u)
            // ✅ use the managerScope so we can cancel it later
            managerScope.launch {
                try {
                    val shifts = u.timetable?.range ?: emptyList()
                    saveDataLocally(record, shifts, u)
                } catch (e: Exception) {
                    Log.e(TAG, "performApiCall error", e)
                    apiCallInProgress = false
                }
            }
        } ?: run {
            apiCallInProgress = false
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

    fun RecordModel.toDataRequest(errorsList: List<ErrorModel>? = null): DataRequest {
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
            wifi_list = this.wifi_list,
            errorlogs =errorsList?: emptyList(),
        )
    }



    private suspend fun callApi(record: RecordModel, user: UserModel) {
        Log.i(TAG, "Calling API with record: ${record}\n at:${Utils.getCurrentDateTime()}")

        val savedIssues = dao.getAllIssues(user?._id.toString()) // List<IssueEntity>

        val errorList = savedIssues.map {
            ErrorModel(
                key = it.issueKey,
                title = it.issueTitle,
                solution = it.solution,
                time = Utils.getUTCFromTimestamp(it.timestamp)
            )
        }
        val records = dao.getAllRecords(user._id.toString())
            .map { it.toDataRequest(errorList) }
            .toMutableList()

        if (Utils.isInternetAvailable(context)) {
            val token = SharedPref.getInstance(context)?.getToken() ?: ""
            try {
                val response = repository.sendData(records, token)
                Log.i(TAG, "MRcallApi: apiResponse:${response.body()}")

                if (response.isSuccessful) {
                    val body = response.body()
                    apiCallInProgress = false

                    // ✅ Check if body contains an error-like status
                    val hasErrorInData = body?.data?.any { it.status.equals("error", ignoreCase = true) } == true
                    if (hasErrorInData) {
                        val errorMessages = body?.data
                            ?.filter { it.status.equals("error", ignoreCase = true) }
                            ?.joinToString("\n") { it.message ?: "Unknown error" }

                        Log.e(TAG, "API logical error: $errorMessages")
                        sendNotificationUpdate(errorMessages ?: "Something went wrong")
                    } else {
                        Log.i(TAG, "API call successful, handling response.")
                        handleSuccessfulResponse(body, record)
                        sendNotificationUpdate("Data synced to admin panel at ${Utils.getCurrentDateTime()}")
                    }
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

    /**
     * Persists a record only if we're currently inside today's effective shift window
     * (multiple timetable if active; otherwise the passed-in `shifts`), using the same
     * -1h/+1h buffer rule as the rest of the app. When outside the window, the service
     * is asked to stop.
     */
    private fun saveDataLocally(record: RecordModel, shifts: List<TimeRange>, user: UserModel) {
        Log.i(TAG, "Saving data locally for record: $record")

        // 1) Pick the effective timetable for TODAY (active multi-table wins; else fallback to 'shifts')
        val todayDate = LocalDate.now()
        val activeMulti = user.multipleTimeTables?.find { mt ->
            val s = mt.startDate.toLocalDate(); val e = mt.endDate.toLocalDate()
            todayDate in s..e
        }
        val effectiveRange: List<TimeRange> = activeMulti?.timetable?.range ?: shifts

        val todayName = getCurrentDayName()
        val todayShift = effectiveRange.find { it.day.equals(todayName, ignoreCase = true) }

        if (todayShift?.start != null && todayShift.end != null) {
            Log.i(TAG, "Today's Shift -> day:${todayShift.day}, start:${todayShift.start}, end:${todayShift.end}")

            // 2) Use your centralized buffer rule (-1h/+1h). This helper should already handle
            //    overnight spans and the +/- buffer (you’re using it elsewhere in the service).
            val insideWindow = ShiftUtils.isTimeWithinBufferRange(
                Calendar.getInstance(),
                todayShift.start,
                todayShift.end
            )

            if (insideWindow) {
                // 3) Perform DB and API work on IO; dedupe by your latest-record rule
                managerScope.launch {
                    try {
                        val latest = dao.getLatestRecord(record.user_id)
                        if (shouldInsertRecord(latest, record)) {
                            dao.insertRecord(record)

                            // Foreground notification update can be posted back to main
                            withContext(Dispatchers.Main) {
                                sendNotificationUpdate("Data stored at ${Utils.getCurrentDateTime()}")
                            }

                            // Trigger your sync/API work
                            callApi(record, user)
                        } else {
                            Log.d("DBDao", "Record not inserted: Time difference <= 5 minutes")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save record/call API", e)
                    }
                }
            } else {
                // 4) Outside window → finish service (keeps behavior consistent with the rest of the flow)
                sendFinishBroadcastAndLog("Current time is outside shift period, NOT scheduling API Worker.")
            }
        } else {
            // OFF or no shift today
            sendFinishBroadcastAndLog("No shift found for today.")
        }
    }

    /** Small helper to keep the stop path tidy + consistent in logs */
    private fun sendFinishBroadcastAndLog(reason: String) {
        Log.i(TAG, reason)
        val intent = Intent("com.shiftsmart.plus.ACTION_FINISH")
        context.sendBroadcast(intent)
    }


//    private fun saveDataLocally(record: RecordModel, shifts: List<TimeRange>, user: UserModel) {
//        Log.i(TAG, "Saving data locally for record: ${record}")
//        val today = getCurrentDayName() // Get today's name (e.g., "Tuesday")
//
//        val todayShift = shifts.find { it.day.equals(today, ignoreCase = true) }
//
//        if (todayShift != null && todayShift.start != null && todayShift.end != null) {
//            Log.i(TAG, "Today's Shift -> day:${todayShift.day}, start:${todayShift.start}, end:${todayShift.end}")
//
//            val startCalendar = getCalendarForShift(todayShift.day, todayShift.start, -1)
//            val endCalendar = getCalendarForShift(todayShift.day, todayShift.end, 1)
//
//            val currentTime = Calendar.getInstance()
//
//            if (startCalendar != null && endCalendar != null) {
//                // Schedule API Worker ONLY IF current time is between shift start & end
//                if (currentTime.after(startCalendar) && currentTime.before(endCalendar)) {
//                    val latest = dao.getLatestRecord(record.user_id)
//                    if (shouldInsertRecord(latest, record)) {
//                        dao.insertRecord(record)
//                        sendNotificationUpdate("Data stored at ${Utils.getCurrentDateTime()}")
//                        CoroutineScope(Dispatchers.IO).launch {
//                            callApi(record,user)
//                        }
//                    } else {
//                        Log.d("DBDao", "Record not inserted: Time difference <= 5 minutes")
//                    }
//                } else {
//                    val intent = Intent("com.shiftsmart.plus.ACTION_FINISH")
//                    context.sendBroadcast(intent)
//                    Log.i(TAG, "Current time is outside shift period, NOT scheduling API Worker.")
//                }
//            }
//        } else {
//            Log.i(TAG, "No shift found for today.")
//            val intent = Intent("com.shiftsmart.plus.ACTION_FINISH")
//            context.sendBroadcast(intent)
//        }
//    }
//    // Don't forget to unbind when done

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

    fun setWifiList(wifiList: MutableList<WifiModel>) {
        wifiScanResults.clear()
        wifiScanResults.addAll(wifiList);
    }

}
