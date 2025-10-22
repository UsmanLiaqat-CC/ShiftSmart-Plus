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
import com.shiftsmart.plus.periodicAction.AlarmReceiver
import com.shiftsmart.plus.repository.MainRepository
import com.shiftsmart.plus.utils.Utils.getCurrentDayName
import com.shiftsmart.plus.utils.Utils.toLocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.Response
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale
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
    private val insertMutex = Mutex() // Prevents concurrent record insertion

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
        Log.i(TAG, "performApiCall: at:${Utils.getCurrentDateTime()}")
//        if (apiCallInProgress) return
//        apiCallInProgress = true

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
//                    apiCallInProgress = false
                }
            }
        } ?: run {
//            apiCallInProgress = false
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
        val allRecords = dao.getAllRecords(user._id.toString())
            .map { it.toDataRequest(errorList) }

        // Filter out duplicate records based on time (without seconds) for default type
        // Also filter consecutive default records with < 4 minutes difference
        val records = mutableListOf<DataRequest>()
        var lastDefaultTime: LocalTime? = null

        for ((index, record) in allRecords.withIndex()) {
            if (record.attendanceType == "default") {
                // Extract time without seconds (HH:mm format)
                val currentTimeKey = try {
                    record.time.substringBeforeLast(':').substringAfter('T')
                } catch (e: Exception) {
                    record.localTime.substringBeforeLast(':')
                }

                // Check if this time already exists in previous records (duplicate check)
                val isDuplicate = allRecords.subList(0, index).any { prevRecord ->
                    if (prevRecord.attendanceType == "default") {
                        val prevTimeKey = try {
                            prevRecord.time.substringBeforeLast(':').substringAfter('T')
                        } catch (e: Exception) {
                            prevRecord.localTime.substringBeforeLast(':')
                        }
                        currentTimeKey == prevTimeKey
                    } else false
                }

                if (isDuplicate) {
                    Log.d(TAG, "Duplicate default record found at time $currentTimeKey, excluding from API call")
                    continue
                }

                // Check if time difference with last default record is < 4 minutes
                try {
                    val formatter = DateTimeFormatter.ofPattern("HH:mm:ss")
                    val currentTime = LocalTime.parse(record.localTime, formatter)

                    if (lastDefaultTime != null) {
                        val minutesDiff = Duration.between(lastDefaultTime, currentTime).toMinutes()
                        if (minutesDiff < 4) {
                            Log.d(TAG, "Consecutive default record found with ${minutesDiff} minutes difference (< 4 min) at time ${record.localTime}, excluding from API call")
                            continue
                        }
                    }

                    lastDefaultTime = currentTime
                    records.add(record)
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing time for record: ${record.localTime}", e)
                    records.add(record) // Add anyway if parsing fails
                }
            } else {
                records.add(record) // Keep all non-default records
            }
        }

        Log.i(TAG, "callApi: Total records: ${allRecords.size}, After deduplication and 4-min filter: ${records.size}")

        if (Utils.isInternetAvailable(context)) {
            val token = SharedPref.getInstance(context)?.getToken() ?: ""
            try {
                val response = repository.sendData(records, token)
                Log.i(TAG, "MRcallApi: apiResponse:${response.body()}")

                if (response.isSuccessful) {
                    val body = response.body()

                    if (body == null) {
                        Log.e(TAG, "API call successful but body is null")
                        sendNotificationUpdate("Empty response from server")
                        return
                    }

                    Log.i(TAG, "callApi: Response body message: ${body.message}")

//                    apiCallInProgress = false
                    // ✅ Check if body contains an error-like status
                    val hasErrorInData = body.data.any { it.status.equals("error", ignoreCase = true) }
                    if (hasErrorInData) {
                        val errorMessages = body.data
                            .filter { it.status.equals("error", ignoreCase = true) }
                            .joinToString("\n") { it.message }

                        body.data.forEach { attendance ->
                            dao.deleteRecordByUuid(attendance.UUID)
                        }
                        Log.e(TAG, "API logical error: $errorMessages")
                        sendNotificationUpdate(errorMessages)
                    } else {
                        Log.i(TAG, "API call successful, handling response.")
                        handleSuccessfulResponse(body, record)
                    }
                } else {

                    Log.e(TAG, "API call failed: ${response.errorBody()}")
                    handleUnsuccessfulResponse(response)
                }

            } catch (e: Exception) {
                Log.e(TAG, "API call exception: ${e.message}")
//                apiCallInProgress = false
            }
        }
        else {

            Log.e(TAG, "No internet available for API call.")
//            apiCallInProgress = false
        }
    }

    private suspend fun handleSuccessfulResponse(
        response: AttendaceResponseModel?,
        record: RecordModel
    ) {
        response?.let { attendanceResponse ->
            // Get the main message from response
            val mainMessage = attendanceResponse.message
            Log.i(TAG, "handleSuccessfulResponse: mainMessage: $mainMessage")

            // Check if main message requires deleting all user records
            if (mainMessage.contains("Multiple attendance records", ignoreCase = true))
            {

                Log.i(TAG, "handleSuccessfulResponse: Deleting all records based on message: $mainMessage")
                sendNotificationUpdate(mainMessage)

                // Delete all records for this user
                val user = SharedPref.getInstance(context)?.getUser()
                user?.let {
                    val userId = it._id.toString()
                    dao.deleteAllRecordsByUserId(userId)
                    Log.i(TAG, "handleSuccessfulResponse: Deleted all records for user: $userId")
                }
            } else {
                // Show main message if not related to deletion
                if (mainMessage.isNotEmpty()) {
                    sendNotificationUpdate(mainMessage)
                }
            }

            // Iterate through attendance data list
            attendanceResponse.data.forEach { attendance ->
                Log.i(TAG, "handleSuccessfulResponse: Processing attendance: $attendance")

                when (attendance.attendanceStatus) {
                    "online" -> {
                        // Delete record by UUID
                        dao.deleteRecordByUuid(attendance.UUID)
                        Log.i(TAG, "handleSuccessfulResponse: Deleted online record UUID: ${attendance.UUID}")
                    }

                    "offline" -> {
                        // If status is "offline", delete corresponding record from database
                        dao.deleteRecordByUuid(attendance.UUID)
                        Log.i(TAG, "handleSuccessfulResponse: Deleted offline record UUID: ${attendance.UUID}")
                    }

                    else -> {
                        // Handle any other status by deleting the record
                        dao.deleteRecordByUuid(attendance.UUID)
                        Log.i(TAG, "handleSuccessfulResponse: Deleted record with status '${attendance.attendanceStatus}', UUID: ${attendance.UUID}")
                    }
                }
            }

            Log.i(TAG, "API data successfully processed and cleaned.")

            // Send final sync notification
            sendNotificationUpdate("Data synced to admin panel at ${Utils.getCurrentDateTime()}")
        }
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
//        apiCallInProgress = false
    }

    /**
     * Persists a record only if we're currently inside today's effective shift window
     * (multiple timetable if active; otherwise the passed-in `shifts`), using the same
     * -1h/+1h buffer rule as the rest of the app. When outside the window, the service
     * is asked to stop.
     */

    fun getPreviousDayName(): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -1)
        return cal.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.ENGLISH)
    }


    private fun saveDataLocally(record: RecordModel, shifts: List<TimeRange>, user: UserModel) {
        val todayDate = LocalDate.now()
        val activeMulti = user.multipleTimeTables?.find { mt ->
            val s = mt.startDate.toLocalDate(); val e = mt.endDate.toLocalDate()
            todayDate in s..e
        }
        val effectiveRange: List<TimeRange> = activeMulti?.timetable?.range ?: shifts

        val todayName = getCurrentDayName()
        val yesterdayName = getPreviousDayName()

        val todayShift = effectiveRange.find { it.day.equals(todayName, ignoreCase = true) }
        val yesterdayShift = effectiveRange.find { it.day.equals(yesterdayName, ignoreCase = true) }

        val currentCal = Calendar.getInstance()
        var insideWindow = false

        if (todayShift?.start != null && todayShift.end != null) {
            insideWindow = ShiftUtils.isTimeWithinBufferRange(currentCal, todayShift.start, todayShift.end)
        }

        // 🔹 if not inside today's shift, check if still inside yesterday's (overnight)
        if (!insideWindow && yesterdayShift?.start != null && yesterdayShift.end != null) {
            insideWindow = ShiftUtils.isTimeWithinBufferRange(currentCal, yesterdayShift.start, yesterdayShift.end)
        }

        if (insideWindow) {
            managerScope.launch {
                insertMutex.withLock {
                    try {
                        val existingCount = dao.countRecordByTime(record.time)
                        if (existingCount > 0) {
                            Log.d(TAG, "Duplicate record found for time: ${record.time}, skipping insert.")
                            return@withLock
                        }

                        val latest = dao.getLatestRecord(record.user_id)
                        val referenceRecord = if (latest != null && latest.attendanceType != StatusEnum.default.name) {
                            dao.getLatestDefaultRecord(record.user_id)
                        } else {
                            latest
                        }

                        Log.i(TAG, "saveDataLocally: inside window using referenceRecord:$referenceRecord\nnewRecord:$record")

                        if (shouldInsertRecord(referenceRecord, record)) {
                            dao.insertRecord(record)
                            withContext(Dispatchers.Main) {
                                sendNotificationUpdate("Data stored at ${Utils.getCurrentDateTime()}")
                            }
                            callApi(record, user)
                        } else {
                            Log.d(TAG, "Record not inserted: Time difference <= 5 minutes")
                        }

                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save record/call API", e)
                    }
                }
            }
        } else {
            sendFinishBroadcastAndLog("Current time is outside shift period, NOT scheduling API Worker.")
        }
    }

    /** Small helper to keep the stop path tidy + consistent in logs */
    private fun sendFinishBroadcastAndLog(reason: String) {
        Log.i(TAG, reason)
        val intent = Intent("com.shiftsmart.plus.ACTION_FINISH")
        context.sendBroadcast(intent)
    }


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

        val formatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        val latestTime = LocalTime.parse(latestRecord.localTime, formatter)
        val newTime = LocalTime.parse(newRecord.localTime, formatter)

        // 1️⃣ if the new record time is BEFORE the latest record → reject immediately
        if (newTime.isBefore(latestTime)) {
            Log.d("DBDao", "Record not inserted: new record time ${newRecord.localTime} is before latest ${latestRecord.localTime}")
            return false
        }
        // 2️⃣ Otherwise check if difference is >= 5 minutes
        val duration = Duration.between(latestTime, newTime).toMinutes()
        return duration >= 5
    }

    fun setWifiList(wifiList: MutableList<WifiModel>) {
        wifiScanResults.clear()
        wifiScanResults.addAll(wifiList);
    }



}
