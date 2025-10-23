package com.shiftsmart.plus.utils

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.maps.model.LatLng
import com.shiftsmart.plus.database.DBDao
import com.shiftsmart.plus.database.RecordModel
import com.shiftsmart.plus.enums.StatusEnum
import com.shiftsmart.plus.models.AttendaceResponseModel
import com.shiftsmart.plus.models.DataRequest
import com.shiftsmart.plus.models.ErrorModel
import com.shiftsmart.plus.models.TimeRange
import com.shiftsmart.plus.models.UserModel
import com.shiftsmart.plus.models.WifiModel
import com.shiftsmart.plus.repository.MainRepository
import com.shiftsmart.plus.utils.Utils.getCurrentDayName
import com.shiftsmart.plus.utils.Utils.toLocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
            uuid = Utils.generateRandomUuid(),
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

        // ✅ DOUBLE-LAYER FILTERING FOR API CALL:
        // Even though we prevent duplicates at insertion, we apply additional filtering here as a safety net
        // This ensures clean data is sent to the API and DELETES invalid records from database

        val records = mutableListOf<DataRequest>()
        val recordsToDelete = mutableListOf<String>() // Track UUIDs of records to delete
        var lastDefaultTime: LocalTime? = null

        for ((index, record) in allRecords.withIndex()) {
            if (record.attendanceType == "default") {
                // ✅ FILTER 1: Check for UTC time duplicates (comparing without seconds)
                // Extract UTC time in HH:mm format from the ISO timestamp
                val currentTimeKey = try {
                    // Extract time from UTC format like "2025-10-22T13:50:01Z"
                    record.time.substringBeforeLast(':').substringAfter('T')
                } catch (e: Exception) {
                    // Fallback to local time if UTC parsing fails
                    record.localTime.substringBeforeLast(':')
                }

                // Check if any previous record has the same UTC time (HH:mm)
                val isDuplicate = allRecords.subList(0, index).any { prevRecord ->
                    if (prevRecord.attendanceType == "default") {
                        val prevTimeKey = try {
                            prevRecord.time.substringBeforeLast(':').substringAfter('T')
                        } catch (e: Exception) {
                            prevRecord.localTime.substringBeforeLast(':')
                        }
                        // Compare UTC times to detect duplicates
                        currentTimeKey == prevTimeKey
                    } else false
                }

                if (isDuplicate) {
                    Log.d(TAG, "❌ Duplicate UTC time $currentTimeKey, UUID: ${record.UUID} - DELETING from database")
                    recordsToDelete.add(record.UUID) // Mark for deletion
                    continue
                }

                // ✅ FILTER 2: Enforce minimum 4-minute gap between consecutive default records
                // This prevents sending multiple records within a short time span
                try {
                    val formatter = DateTimeFormatter.ofPattern("HH:mm:ss")
                    val currentTime = LocalTime.parse(record.localTime, formatter)

                    if (lastDefaultTime != null) {
                        val minutesDiff = Duration.between(lastDefaultTime, currentTime).toMinutes()

                        // Reject if gap is less than 4 minutes
                        if (minutesDiff < 4) {
                            Log.d(TAG, "❌ Time gap ${minutesDiff} min (< 4 min) at ${record.localTime}, UUID: ${record.UUID} - DELETING from database")
                            recordsToDelete.add(record.UUID) // Mark for deletion
                            continue
                        }

                        Log.d(TAG, "✅ Record accepted: ${minutesDiff} minutes gap from previous record, UUID: ${record.UUID}")
                    }

                    // Update the last processed time for next iteration
                    lastDefaultTime = currentTime
                    records.add(record)
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing time for record: ${record.localTime}", e)
                    records.add(record) // Add anyway if parsing fails to avoid data loss
                }
            } else {
                // ✅ Non-default records (manual check-in/out) are always included
                records.add(record)
            }
        }

        // ✅ DELETE invalid records from database immediately
        if (recordsToDelete.isNotEmpty()) {
            Log.i(TAG, "Deleting ${recordsToDelete.size} invalid records from database")
            recordsToDelete.forEach { uuid ->
                dao.deleteRecordByUuid(uuid)
            }
        }

        Log.i(TAG, "callApi: Total records: ${allRecords.size}, After deduplication and 4-min filter: ${records.size}")

        // Round time differences to 5 minutes before sending to API
        val adjustedRecords = mutableListOf<DataRequest>()
        for (i in records.indices) {
            val currentRecord = records[i]

            if (i > 0) {
                val previousRecord = adjustedRecords[i - 1]

                try {
                    // Parse UTC times to calculate difference
                    val utcFormatter = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.getDefault())
                    utcFormatter.timeZone = java.util.TimeZone.getTimeZone("UTC")
                    val prevUtcTime = utcFormatter.parse(previousRecord.time)
                    val currUtcTime = utcFormatter.parse(currentRecord.time)

                    if (prevUtcTime != null && currUtcTime != null) {
                        val diffMinutes = ((currUtcTime.time - prevUtcTime.time) / (1000 * 60)).toInt()

                        // Adjust time if difference is 4 or 6 minutes
                        when (diffMinutes) {
                            4 -> {
                                // Increase by 1 minute to make it 5 minutes
                                val calendar = java.util.Calendar.getInstance()
                                calendar.time = currUtcTime
                                calendar.add(java.util.Calendar.MINUTE, 1)

                                val adjustedUtcTime = utcFormatter.format(calendar.time)

                                // Adjust local time as well
                                val localFormatter = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                                val currLocalTime = localFormatter.parse(currentRecord.localTime)
                                if (currLocalTime != null) {
                                    val localCalendar = java.util.Calendar.getInstance()
                                    localCalendar.time = currLocalTime
                                    localCalendar.add(java.util.Calendar.MINUTE, 1)
                                    val adjustedLocalTime = localFormatter.format(localCalendar.time)

                                    adjustedRecords.add(currentRecord.copy(
                                        time = adjustedUtcTime,
                                        localTime = adjustedLocalTime
                                    ))
                                    Log.i(TAG, "Adjusted record from 4 min to 5 min: ${currentRecord.localTime} -> $adjustedLocalTime")
                                } else {
                                    adjustedRecords.add(currentRecord.copy(time = adjustedUtcTime))
                                }
                            }
                            6 -> {
                                // Decrease by 1 minute to make it 5 minutes
                                val calendar = java.util.Calendar.getInstance()
                                calendar.time = currUtcTime
                                calendar.add(java.util.Calendar.MINUTE, -1)

                                val adjustedUtcTime = utcFormatter.format(calendar.time)

                                // Adjust local time as well
                                val localFormatter = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                                val currLocalTime = localFormatter.parse(currentRecord.localTime)
                                if (currLocalTime != null) {
                                    val localCalendar = java.util.Calendar.getInstance()
                                    localCalendar.time = currLocalTime
                                    localCalendar.add(java.util.Calendar.MINUTE, -1)
                                    val adjustedLocalTime = localFormatter.format(localCalendar.time)

                                    adjustedRecords.add(currentRecord.copy(
                                        time = adjustedUtcTime,
                                        localTime = adjustedLocalTime
                                    ))
                                    Log.i(TAG, "Adjusted record from 6 min to 5 min: ${currentRecord.localTime} -> $adjustedLocalTime")
                                } else {
                                    adjustedRecords.add(currentRecord.copy(time = adjustedUtcTime))
                                }
                            }
                            else -> {
                                // Keep as is for other differences
                                adjustedRecords.add(currentRecord)
                            }
                        }
                    } else {
                        adjustedRecords.add(currentRecord)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error adjusting time difference: ${e.message}")
                    adjustedRecords.add(currentRecord)
                }
            } else {
                // First record, no adjustment needed
                adjustedRecords.add(currentRecord)
            }
        }

        if (Utils.isInternetAvailable(context)) {
            val token = SharedPref.getInstance(context)?.getToken() ?: ""
            try {
                val response = repository.sendData(adjustedRecords, token)
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
                        // ✅ STEP 1: Check for exact UTC time duplicate
                        // This prevents inserting records with the exact same UTC timestamp
                        val existingCount = dao.countRecordByTime(record.time)
                        if (existingCount > 0) {
                            Log.d(TAG, "❌ Duplicate record found for UTC time: ${record.time}, skipping insert.")
                            return@withLock
                        }

                        // ✅ STEP 2: Get the latest record for time difference validation
                        val latest = dao.getLatestRecord(record.user_id)
                        val referenceRecord = if (latest != null && latest.attendanceType != StatusEnum.default.name) {
                            dao.getLatestDefaultRecord(record.user_id)
                        } else {
                            latest
                        }

                        Log.i(TAG, "saveDataLocally: inside window using referenceRecord:$referenceRecord\nnewRecord:$record")

                        // ✅ STEP 3: Validate using 4-minute gap enforcement
                        // This applies to ALL record types and ensures:
                        // - No out-of-order time insertions
                        // - Minimum 4-minute gap between consecutive records
                        if (shouldInsertRecord(referenceRecord, record)) {
                            dao.insertRecord(record)
                            withContext(Dispatchers.Main) {
                                sendNotificationUpdate("Data stored at ${Utils.getCurrentDateTime()}")
                            }
                            callApi(record, user)
                        } else {
                            Log.d(TAG, "❌ Record not inserted: Failed shouldInsertRecord validation (either time is before latest or gap < 4 minutes)")
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
    /**
     * Validates whether a new record should be inserted based on time comparison with the latest record.
     *
     * This function performs two critical checks:
     * 1. Ensures the new record's time is not before the latest record (prevents out-of-order insertions)
     * 2. Enforces a minimum 4-minute gap between consecutive records
     *
     * @param latestRecord The most recent record in the database for this user (can be null if no records exist)
     * @param newRecord The new record that we want to insert
     * @return true if the record should be inserted, false otherwise
     */
    private fun shouldInsertRecord(
        latestRecord: RecordModel?,
        newRecord: RecordModel
    ): Boolean {
        // ✅ If no previous record exists, allow insertion
        if (latestRecord == null) return true

        val formatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        val latestTime = LocalTime.parse(latestRecord.localTime, formatter)
        val newTime = LocalTime.parse(newRecord.localTime, formatter)

        // ✅ VALIDATION 1: Reject if new record time is BEFORE the latest record
        // This prevents inserting records with earlier timestamps than what's already in the database
        if (newTime.isBefore(latestTime)) {
            Log.d(TAG, "Record not inserted: new record time ${newRecord.localTime} is before latest ${latestRecord.localTime}")
            return false
        }

        // ✅ VALIDATION 2: Check if time difference is at least 4 minutes
        // This ensures we don't insert records too frequently (minimum 4-minute gap required)
        val duration = Duration.between(latestTime, newTime).toMinutes()
        if (duration < 4) {
            Log.d(TAG, "Record not inserted: Time difference is ${duration} minutes (< 4 minutes required)")
            return false
        }

        Log.d(TAG, "shouldInsertRecord validation passed: ${duration} minutes gap between records")
        return true
    }

    fun setWifiList(wifiList: MutableList<WifiModel>) {
        wifiScanResults.clear()
        wifiScanResults.addAll(wifiList);
    }



}
