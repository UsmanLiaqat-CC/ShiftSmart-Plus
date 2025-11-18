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
import com.shiftsmart.plus.periodicAction.ShiftRestartAlarmManager
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject

class AttendanceSyncManager @Inject constructor(
    private val context: Context,
    private val repository: MainRepository,
    private val dao: DBDao
)
{
    private val TAG = "AttendanceSyncManager"
    private val managerJob = SupervisorJob()
    private val managerScope = CoroutineScope(Dispatchers.IO + managerJob)
    private val insertMutex = Mutex() // Prevents concurrent record insertion

    /**
     * Saves a record to local database with shift validation.
     * This is called from MyService after creating a record every 5 minutes.
     * After saving, automatically syncs to server if internet is available.
     *
     * @return true if inside shift window (service should continue), false if off-shift (service should stop)
     */

    // for version 8
//    suspend fun saveRecordLocally(record: RecordModel, user: UserModel): Boolean {
//        val shifts = user.timetable?.range ?: emptyList()
//        return saveDataLocally(record, shifts, user)
//    }


// In AttendanceSyncManager.kt
    fun saveRecordLocally(
        record: RecordModel,
        user: UserModel,
        callback: (isInsideShift: Boolean) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val shifts = user.timetable?.range ?: emptyList()
                val result = saveDataLocally(record, shifts, user)
                callback(result)
            } catch (e: Exception) {
                Log.e("AttendanceSyncManager", "Error saving record", e)
                callback(false)
            }
        }
    }

    private fun saveDataLocally(record: RecordModel, shifts: List<TimeRange>, user: UserModel): Boolean {
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
        val currentTimeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(currentCal.time)
        var insideWindow = false

        if (todayShift?.start != null && todayShift.end != null) {
            insideWindow = ShiftUtils.isTimeWithinBufferRange(currentCal, todayShift.start, todayShift.end)
            Log.d(TAG, "📅 Today ($todayName) shift check: ${todayShift.start} - ${todayShift.end} | Current: $currentTimeStr | Result: ${if (insideWindow) "✅ INSIDE" else "❌ OUTSIDE"}")
        } else {
            Log.d(TAG, "📅 Today ($todayName) has no shift configured")
        }

        // 🔹 if not inside today's shift, check if still inside yesterday's (overnight)
        if (!insideWindow && yesterdayShift?.start != null && yesterdayShift.end != null) {
            // ✅ CRITICAL FIX: Pass -1 to check yesterday's shift that extends into today
            insideWindow = ShiftUtils.isTimeWithinBufferRange(currentCal, yesterdayShift.start, yesterdayShift.end, -1)
            Log.d(TAG, "📅 Yesterday ($yesterdayName) shift check (overnight): ${yesterdayShift.start} - ${yesterdayShift.end} | Current: $currentTimeStr | Result: ${if (insideWindow) "✅ INSIDE" else "❌ OUTSIDE"}")
        } else if (!insideWindow) {
            Log.d(TAG, "📅 Yesterday ($yesterdayName) has no shift configured or already checked today's shift")
        }

        if (insideWindow) {
            managerScope.launch {
                insertMutex.withLock {
                    try {
                        // ✅ STEP 1: Validate 5-minute alignment (skip non-default records like ARRIVAL/DEPARTURE)
                        if (record.attendanceType == StatusEnum.default.name) {
                            val recordTime = Utils.parseFlexibleTime(record.localTime)
                            if (recordTime != null) {
                                val recordMinute = recordTime.minute

                                // Check if on 5-minute boundary
                                if (recordMinute % 5 != 0) {
                                    Log.w(TAG, "⏭️ Record time ${record.localTime} NOT on 5-min boundary (${recordMinute}m) → SKIPPING insert")
                                    return@withLock
                                }

                                // Check gap from last record
                                val sharedPref = SharedPref.getInstance(context)
                                val lastSyncTimestamp = sharedPref?.getLastSyncTimestamp() ?: 0L

                                if (lastSyncTimestamp > 0L) {
                                    // Calculate current record timestamp
                                    val currentCal = Calendar.getInstance().apply {
                                        set(Calendar.HOUR_OF_DAY, recordTime.hour)
                                        set(Calendar.MINUTE, recordTime.minute)
                                        set(Calendar.SECOND, 0)
                                        set(Calendar.MILLISECOND, 0)
                                    }
                                    val currentTimestamp = currentCal.timeInMillis
                                    val gapMillis = currentTimestamp - lastSyncTimestamp
                                    val minutesDiff = (gapMillis / (60 * 1000)).toInt()

                                    Log.i(TAG, "⏱️ Gap check: Last sync at ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(lastSyncTimestamp)}, Current: ${record.localTime}, Gap: $minutesDiff min")

                                    when {
                                        minutesDiff < 5 -> {
                                            Log.w(TAG, "⏸️ Gap too small ($minutesDiff min < 5) → SKIPPING insert")
                                            return@withLock
                                        }
                                        minutesDiff % 5 != 0 -> {
                                            Log.w(TAG, "⚠️ Gap not multiple of 5 ($minutesDiff min) → SKIPPING insert")
                                            return@withLock
                                        }
                                        else -> {
                                            Log.i(TAG, "✅ Gap is valid ($minutesDiff min) → proceeding with insert")
                                        }
                                    }
                                } else {
                                    Log.i(TAG, "🆕 No previous record - first sync")
                                }
                            }
                        }

                        // ✅ STEP 2: Check for exact UTC time duplicate
                        // This prevents inserting records with the exact same UTC timestamp
                        val existingCount = dao.countRecordByTime(record.time)
                        if (existingCount > 0) {
                            Log.d(TAG, "❌ Duplicate record found for UTC time: ${record.time}, skipping insert.")
                            return@withLock
                        }


                        // ✅ STEP 3: Insert the record directly
                        // Note: Gap filling is handled during API sync (callApi), not here
                        dao.insertRecord(record)

                        if (record.attendanceType == StatusEnum.default.name) {
                            // ✅ Save last sync time to SharedPreferences (only for default records)
                            SharedPref.getInstance(context)?.saveLastSyncTime(record.localTime)
                            Log.i(TAG, "💾 Record saved at ${record.localTime} | Last sync time updated")

                            withContext(Dispatchers.Main) {
                                sendNotificationUpdate("Data stored at ${Utils.getCurrentDateTime()}")
                            }
                        } else {
                            // For ARRIVAL/DEPARTURE records
                            Log.i(TAG, "💾 ${record.attendanceType} record saved at ${record.localTime}")
                            withContext(Dispatchers.Main) {
                                sendNotificationUpdate("${record.attendanceType} recorded at ${Utils.getCurrentDateTime()}")
                            }
                        }

                        // Sync to API after insertion (API sync handles gap filling and validation)
                        callApi(user)

                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save record/call API", e)
                    }
                }
            }
            return true // Inside shift window, service should continue
        } else {
            // Build detailed reason message
            val reasonBuilder = StringBuilder()
            reasonBuilder.append("❌ Current time is OUTSIDE shift period\n")
            reasonBuilder.append("⏰ Current Time: $currentTimeStr\n")
            reasonBuilder.append("📅 Today: $todayName\n")

            if (todayShift != null) {
                reasonBuilder.append("   Today's Shift: ${todayShift.start} - ${todayShift.end} (with ±1h buffer)\n")
                reasonBuilder.append("   Status: ❌ OUTSIDE\n")
            } else {
                reasonBuilder.append("   Today's Shift: Not configured\n")
            }

            reasonBuilder.append("📅 Yesterday: $yesterdayName\n")
            if (yesterdayShift != null) {
                reasonBuilder.append("   Yesterday's Shift: ${yesterdayShift.start} - ${yesterdayShift.end} (with ±1h buffer)\n")
                reasonBuilder.append("   Status: ❌ OUTSIDE\n")
            } else {
                reasonBuilder.append("   Yesterday's Shift: Not configured\n")
            }

            reasonBuilder.append("🛑 NOT scheduling API Worker - Service will stop")

            sendFinishBroadcastAndLog(reasonBuilder.toString())
            return false // Outside shift window, service should stop
        }
    }


    /**
     * Public method to trigger sync process manually (e.g., midnight sync, on-demand sync)
     */
    suspend fun startSyncProcess() {
        val user = SharedPref.getInstance(context)?.getUser()
        if (user != null) {
            callApi(user)
        } else {
            Log.e(TAG, "❌ Cannot start sync - no user found")
        }
    }



    /**
     * Checks if current time is within shift window with comprehensive logging.
     * Used by MyService to determine if it should continue running.
     * This is the complete shift validation logic with detailed logs.
     *
     * @return true if inside shift, false if off-shift
     */
    fun shouldRunCheck(user: UserModel): Boolean {
        val today = LocalDate.now()
        val currentTimeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date())
        val todayDayName = getCurrentDayName()

        // Get yesterday's day name for overnight shift checking
        val yesterdayCalendar = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val yesterdayDayName = yesterdayCalendar.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.ENGLISH) ?: ""

        // Pick active multiple timetable (if any)
        val activeMulti = user.multipleTimeTables?.find { mt ->
            val s = mt.startDate.toLocalDate()
            val e = mt.endDate.toLocalDate()
            today in s..e
        }

        // Log which timetable is being used
        if (activeMulti != null) {
            Log.i(TAG, "📅 Using MULTIPLE TIMETABLE: ${activeMulti.timetable.timeTableName}")
            Log.i(TAG, "   Active from ${activeMulti.startDate} to ${activeMulti.endDate}")
        } else {
            Log.i(TAG, "📅 Using DEFAULT TIMETABLE")
        }

        // Get active timetable range (default if none)
        val range = activeMulti?.timetable?.range ?: user.timetable?.range ?: return false
        val now = Calendar.getInstance()

        // Log all shift windows for debugging
        Log.i(TAG, "🕐 Current time: $currentTimeStr on $todayDayName")
        Log.i(TAG, "📋 Checking ${range.size} shift(s) in timetable:")
        range.forEach { shift ->
            val isThisDayShift = shift.day.equals(todayDayName, ignoreCase = true)
            val marker = if (isThisDayShift) "→" else " "
            Log.i(TAG, "  $marker ${shift.day}: ${shift.start} - ${shift.end} (with ±1h buffer)")
        }

        // ✅ STEP 1: Check if we're inside TODAY's shift
        val todayShift = range.find { it.day.equals(todayDayName, ignoreCase = true) }
        var isInsideShift = false

        if (todayShift?.start != null && todayShift.end != null) {
            isInsideShift = ShiftUtils.isTimeWithinBufferRange(now, todayShift.start, todayShift.end)
            Log.i(TAG, "📅 Today ($todayDayName) shift: ${todayShift.start} - ${todayShift.end} | Result: ${if (isInsideShift) "✅ INSIDE" else "❌ OUTSIDE"}")

            if (isInsideShift) {
                Log.i(TAG, "✅ INSIDE shift window: $todayDayName (${todayShift.start} - ${todayShift.end} with ±1h buffer)")
            }
        } else {
            Log.i(TAG, "📅 Today ($todayDayName) has no shift configured")
        }

        // ✅ STEP 2: If not inside today's shift, check YESTERDAY's overnight shift
        if (!isInsideShift) {
            val yesterdayShift = range.find { it.day.equals(yesterdayDayName, ignoreCase = true) }

            if (yesterdayShift?.start != null && yesterdayShift.end != null) {
                // ✅ CRITICAL: Pass -1 to check yesterday's shift that extends into today
                isInsideShift = ShiftUtils.isTimeWithinBufferRange(now, yesterdayShift.start, yesterdayShift.end, -1)
                Log.i(TAG, "📅 Yesterday ($yesterdayDayName) shift check (overnight): ${yesterdayShift.start} - ${yesterdayShift.end} | Result: ${if (isInsideShift) "✅ INSIDE" else "❌ OUTSIDE"}")

                if (isInsideShift) {
                    Log.i(TAG, "✅ INSIDE shift window: Yesterday's $yesterdayDayName shift extends into today (${yesterdayShift.start} - ${yesterdayShift.end} with ±1h buffer)")
                }
            } else {
                Log.i(TAG, "📅 Yesterday ($yesterdayDayName) has no shift configured")
            }
        }

        if (!isInsideShift) {
            Log.w(TAG, "❌ NOT inside any shift window at $currentTimeStr")
            Log.w(TAG, "💡 Note: Checked both today's shift and yesterday's overnight shift")
        }

        Log.i(TAG, "shouldRunCheck → insideShift=$isInsideShift at ${Utils.getCurrentDateTime()}")
        return isInsideShift
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



    private suspend fun callApi(user: UserModel) {
        Log.i(TAG, "Calling API to sync all records at: ${Utils.getCurrentDateTime()}")

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

        Log.i(TAG, "🔍 STARTING COMPREHENSIVE VALIDATION - Total records: ${allRecords.size}")

        // ✅ STEP 1: Sort all records by UTC TIMESTAMP (not local time) to handle midnight crossover
        val utcFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        utcFormatter.timeZone = java.util.TimeZone.getTimeZone("UTC")

        val sortedRecords = allRecords.sortedBy {
            try {
                val utcDate = utcFormatter.parse(it.time)
                utcDate?.time ?: Long.MAX_VALUE // Use timestamp for sorting
            } catch (e: Exception) {
                Long.MAX_VALUE // Put malformed records at the end
            }
        }

        Log.i(TAG, "📋 Sorted records by UTC timestamp (handles midnight crossover)")

        // ✅ STEP 2: Separate default and non-default records
        val defaultRecords = sortedRecords.filter { it.attendanceType == "default" }
        val nonDefaultRecords = sortedRecords.filter { it.attendanceType != "default" }

        Log.i(TAG, "📊 Default records: ${defaultRecords.size}, Non-default records: ${nonDefaultRecords.size}")

        // ✅ STEP 3: Validate default records - only delete invalid ones, don't add dummy records
        val validatedDefaultRecords = mutableListOf<DataRequest>()
        val recordsToDelete = mutableListOf<String>()

        for (i in defaultRecords.indices) {
            val currentRecord = defaultRecords[i]

            try {
                // For the first record, just add it
                if (i == 0) {
                    validatedDefaultRecords.add(currentRecord)
                    Log.d(TAG, "✅ First default record: ${currentRecord.localTime}")
                    continue
                }

                val previousRecord = validatedDefaultRecords.last()

                // Compare UTC times without seconds (HH:mm only)
                val prevUtcTimeHHMM = getUtcTimeHHMM(previousRecord.time)
                val currUtcTimeHHMM = getUtcTimeHHMM(currentRecord.time)

                // Calculate minute difference using UTC time without seconds
                val minutesDiff = calculateMinutesDifferenceHHMM(prevUtcTimeHHMM, currUtcTimeHHMM)

                Log.d(TAG, "⏱️ Comparing: ${previousRecord.localTime} (UTC: $prevUtcTimeHHMM) -> ${currentRecord.localTime} (UTC: $currUtcTimeHHMM) = $minutesDiff min")

                if (minutesDiff == 5) {
                    // Perfect 5-minute interval
                    validatedDefaultRecords.add(currentRecord)
                    Log.d(TAG, "✅ Perfect 5-min interval: ${currentRecord.localTime}")

                } else if (minutesDiff > 5 && minutesDiff % 5 == 0) {
                    // Gap detected - just log it, don't fill with dummy records
                    Log.i(TAG, "⚠️ Gap detected: $minutesDiff minutes - continuing without backfill")

                    // Add current record (skip the gap)
                    validatedDefaultRecords.add(currentRecord)
                    Log.d(TAG, "✅ Current record added (gap not filled): ${currentRecord.localTime}")

                } else if (minutesDiff > 5) {
                    // Gap exists but not a clean multiple of 5 - still accept the record
                    Log.i(TAG, "⚠️ Irregular gap: $minutesDiff minutes - accepting record anyway")
                    validatedDefaultRecords.add(currentRecord)
                    Log.d(TAG, "✅ Record accepted despite irregular gap: ${currentRecord.localTime}")

                } else if (minutesDiff < 5 && minutesDiff > 0) {
                    // Too close together - delete this record
                    Log.e(TAG, "❌ Records too close: $minutesDiff min (< 5 min) - DELETING ${currentRecord.localTime}, UUID: ${currentRecord.UUID}")
                    recordsToDelete.add(currentRecord.UUID)

                } else if (minutesDiff <= 0) {
                    // Duplicate or backwards time - delete this record
                    Log.e(TAG, "❌ Invalid time sequence: $minutesDiff min - DELETING ${currentRecord.localTime}, UUID: ${currentRecord.UUID}")
                    recordsToDelete.add(currentRecord.UUID)

                } else {
                    // Any other invalid case
                    Log.e(TAG, "❌ Invalid interval: $minutesDiff min - DELETING ${currentRecord.localTime}, UUID: ${currentRecord.UUID}")
                    recordsToDelete.add(currentRecord.UUID)
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error processing record ${currentRecord.localTime}: ${e.message}", e)
                recordsToDelete.add(currentRecord.UUID)
            }
        }

        // ✅ STEP 4: Delete invalid records from database
        if (recordsToDelete.isNotEmpty()) {
            Log.i(TAG, "🗑️ Deleting ${recordsToDelete.size} invalid records from database")
            recordsToDelete.forEach { uuid ->
                try {
                    dao.deleteRecordByUuid(uuid)
                    Log.d(TAG, "✅ Deleted record UUID: $uuid")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to delete record: ${e.message}")
                }
            }
        }

        // ✅ STEP 5: Combine validated default records with non-default records
        val finalRecords = mutableListOf<DataRequest>()
        finalRecords.addAll(validatedDefaultRecords)
        finalRecords.addAll(nonDefaultRecords)

        // ✅ STEP 6: Sort final list by local time (ascending order)
        val sortedFinalRecords = finalRecords.sortedBy {
            try {
                Utils.parseFlexibleTime(it.localTime) ?: LocalTime.MAX
            } catch (e: Exception) {
                LocalTime.MAX // Put errors at the end
            }
        }

        Log.i(TAG, "📤 VALIDATION COMPLETE:")
        Log.i(TAG, "   - Original records: ${allRecords.size}")
        Log.i(TAG, "   - Invalid records deleted: ${recordsToDelete.size}")
        Log.i(TAG, "   - Final records to send: ${sortedFinalRecords.size}")
        Log.i(TAG, "   - Records sorted by local time in ascending order ✅")
        Log.i(TAG, "   - Gaps preserved (no backfilling) ✅")

        // Log the final sequence for verification
        sortedFinalRecords.forEachIndexed { index, record ->
            Log.d(TAG, "   [$index] ${record.localTime} - ${record.attendanceType} - UTC: ${getUtcTimeHHMM(record.time)}")
        }

        val records = sortedFinalRecords

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
                        handleSuccessfulResponse(body)
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
        response: AttendaceResponseModel?
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


    /**
     * Called when service needs to stop because we're OUTSIDE shift window.
     * This method handles the graceful shutdown and schedules automatic restart
     * for the next shift using a DUAL REDUNDANCY approach.
     *
     * WHEN THIS IS CALLED:
     * - When current time is outside today's shift (with ±1h buffer)
     * - When current time is outside yesterday's overnight shift
     * - When shift ends normally
     * - When service detects it shouldn't be running
     *
     * WHAT HAPPENS NEXT (DUAL APPROACH):
     *
     * ✅ APPROACH 1 - AlarmManager (PRIMARY):
     *    - Schedules exact alarm for next shift start time
     *    - Requires SCHEDULE_EXACT_ALARM permission on Android 12+
     *    - Best for precise timing when permissions are granted
     *    - May be delayed by Doze mode on some manufacturers
     *
     * ✅ APPROACH 2 - WorkManager (BACKUP):
     *    - Schedules background work for same shift start time
     *    - More resilient to battery optimization
     *    - Survives better on Xiaomi/Samsung/Huawei devices
     *    - Acts as safety net if AlarmManager is cancelled/delayed
     *
     * HOW THEY WORK TOGETHER:
     * - Both are scheduled for the SAME time (next shift start)
     * - Whichever triggers first will check if service is already running
     * - If service is running, the second one does nothing (prevents double-start)
     * - This provides 99%+ reliability across all devices
     *
     * EXAMPLES:
     * - Shift ends at 18:00, next shift starts at 09:00 tomorrow
     *   → Both AlarmManager and WorkManager scheduled for 08:50 tomorrow (10 min before)
     *
     * - Xiaomi device kills AlarmManager at 08:50
     *   → WorkManager backup triggers at 08:50-09:00 range and starts service
     *
     * - Samsung device delays WorkManager to 09:05 due to Doze
     *   → AlarmManager already started service at 08:50
     *   → WorkManager sees service running and does nothing
     *
     * @param reason Detailed reason why service is stopping (for logging)
     */
    private fun sendFinishBroadcastAndLog(reason: String) {
        Log.i(TAG, reason)

        // ✅ CRITICAL: Schedule BOTH alarm mechanisms BEFORE stopping service
        // This ensures automatic restart at next shift time
        val user = SharedPref.getInstance(context)?.getUser()
        if (user != null) {
            // This single call schedules BOTH:
            // 1. AlarmManager (exact alarm - primary method)
            // 2. WorkManager (background work - backup method)
            ShiftRestartAlarmManager.scheduleNextShiftAlarm(context, user)

            Log.i(TAG, "✅ Scheduled DUAL restart mechanisms (AlarmManager + WorkManager)")
            Log.i(TAG, "   → Service will auto-start at next shift time")
            Log.i(TAG, "   → Even if device is locked or in Doze mode")
        } else {
            Log.e(TAG, "❌ Cannot schedule restart - no user found")
        }

        // Broadcast to service to initiate shutdown
        val intent = Intent("com.shiftsmart.plus.ACTION_FINISH")
        context.sendBroadcast(intent)

        Log.i(TAG, "🛑 Service stop broadcast sent - waiting for next shift...")
    }


    private fun sendNotificationUpdate(message: String) {
        Log.i(TAG, "sendNotificationUpdate: on${Utils.getCurrentDateTime()}-->message:${message}")
        val intent = Intent("UPDATE_NOTIFICATION")
        intent.putExtra("message", message)
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
    }

    /**
     * Calculates the difference in minutes between two UTC time strings in HH:mm format.
     * This compares times WITHOUT seconds for more accurate 5-minute interval detection.
     *
     * @param utcTimeHHMM1 First UTC time in "HH:mm" format
     * @param utcTimeHHMM2 Second UTC time in "HH:mm" format
     * @return Difference in minutes (always positive)
     */
    private fun calculateMinutesDifferenceHHMM(utcTimeHHMM1: String, utcTimeHHMM2: String): Int {
        return try {
            val formatter = DateTimeFormatter.ofPattern("HH:mm")
            val time1 = LocalTime.parse(utcTimeHHMM1, formatter)
            val time2 = LocalTime.parse(utcTimeHHMM2, formatter)

            Math.abs(Duration.between(time1, time2).toMinutes()).toInt()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error calculating time difference: ${e.message}", e)
            0
        }
    }



    /**
     * Extracts UTC time in HH:mm format (without seconds and date) from a full UTC timestamp.
     * Example: "2025-10-28T05:15:23Z" -> "05:15"
     */
    private fun getUtcTimeHHMM(utcTime: String): String {
        return try {
            val timePart = utcTime.substringAfter('T').substringBefore('Z')
            timePart.substringBeforeLast(':')
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error extracting UTC HH:mm: ${e.message}", e)
            "00:00"
        }
    }




}
