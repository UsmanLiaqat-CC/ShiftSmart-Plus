package com.shiftsmart.plus.service
import com.shiftsmart.plus.database.RecordModel
import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RequiresApi
import com.shiftsmart.plus.R
import com.shiftsmart.plus.database.DBDao
import com.shiftsmart.plus.database.ShiftSmartPlusDatabase
import com.shiftsmart.plus.enums.StatusEnum
import com.shiftsmart.plus.models.AttendaceResponseModel
import com.shiftsmart.plus.models.DataRequest
import com.shiftsmart.plus.models.ErrorDetail
import com.shiftsmart.plus.models.UserModel
import com.shiftsmart.plus.repository.MainRepository
import com.shiftsmart.plus.utils.SharedPref
import com.shiftsmart.plus.utils.Utils
import com.shiftsmart.plus.utils.parseErrorBody
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import retrofit2.Response
import java.util.*
import javax.inject.Inject
/**
 * Created by Usman Liaqat on 28,Jan,2025
 * usmanliaqat@codecoytechnologies.com,
 * CodeCoy Technologies,
 * Lahore, Pakistan.
 */
import android.app.*
import android.content.*
import android.location.LocationManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.shiftsmart.plus.models.WifiModel
import com.shiftsmart.plus.utils.Utils.getCurrentDateTime
import com.shiftsmart.plus.utils.WifiScanner

import kotlinx.coroutines.*
import java.util.*

@AndroidEntryPoint
class MyForegroundService : Service() {

    @Inject lateinit var repository: MainRepository
    @Inject lateinit var db: ShiftSmartPlusDatabase
    @Inject lateinit var context: Context
    @Inject
    lateinit var locationManager: LocationManager
    @Inject
    lateinit var locationTrack: LocationTrack

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var notification: Notification? = null
    private var notificationManager: NotificationManager? = null
    private var timer: Timer? = null
    private val TAG = "MyForegroundService"
    private lateinit var dao: DBDao
    private var serviceStartTime: Long = 0

    @SuppressLint("ForegroundServiceType")
    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onCreate() {
        super.onCreate()
        dao = db.dbDao()
        startServiceIfNeeded()
    }

    @SuppressLint("ForegroundServiceType")
    private fun startServiceIfNeeded() {
        val user = SharedPref.getInstance(applicationContext)?.getUser()
        val timetable = user?.timetable?.range ?: emptyList()
        val currentDay = Utils.getCurrentDay()
        val todaySchedule = timetable.find { it.day == currentDay }

        if (todaySchedule?.start == null || todaySchedule.end == null) {
            Log.i(TAG, "No working hours set for today ($currentDay), service won't start.")
            stopSelf()
            return
        }

        val startTimeMillis = Utils.convertToMillis(todaySchedule.start) - 3600000 // 1 hour before
        val endTimeMillis = Utils.convertToMillis(todaySchedule.end) + 3600000     // 1 hour after

        notification = createNotification("Service will run from ${todaySchedule.start} to ${todaySchedule.end}")
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(FOREGROUND_SERVICE_ID, notification!!, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(FOREGROUND_SERVICE_ID, notification!!)
        }

        scheduleService(startTimeMillis, endTimeMillis)
    }
    private fun stopServiceAtEnd() {
        val user = SharedPref.getInstance(applicationContext)?.getUser()
        val timetable = user?.timetable?.range ?: emptyList()
        val currentDay = Utils.getCurrentDay()
        val todaySchedule = timetable.find { it.day == currentDay }

        if (todaySchedule?.start == null || todaySchedule.end == null) {
            Log.i(TAG, "No working hours set for today ($currentDay), service won't start.")
            stopSelf()
            return
        }

        val startTimeMillis = Utils.convertToMillis(todaySchedule.start) - 3600000 // 1 hour before
        val endTimeMillis = Utils.convertToMillis(todaySchedule.end) + 3600000     // 1 hour after

        val currentTime = System.currentTimeMillis()
        when {
            else -> {
                Log.i(TAG, "Service stopped as it's past working hours.")
                stopSelf()
            }
        }
        // Stop service when end time is reached
        serviceScope.launch {
            delay(endTimeMillis - currentTime)
            Log.i(TAG, "Stopping service at ${Date(endTimeMillis)}")
            stopSelf()
        }
    }

    private fun scheduleService(startTime: Long, endTime: Long) {
        val currentTime = System.currentTimeMillis()

        when {
            currentTime < startTime -> {
                Log.i(TAG, "Service scheduled to start at ${Date(startTime)}")
                serviceScope.launch {
                    delay(startTime - currentTime)
                    startServiceActions()
                }
            }
            currentTime in startTime..endTime -> {
                Log.i(TAG, "Service running within allowed time range.")
                startServiceActions()
            }
            else -> {
                Log.i(TAG, "Service stopped as it's past working hours.")
                stopSelf()
            }
        }

        // Stop service when end time is reached
        serviceScope.launch {
            delay(endTime - System.currentTimeMillis())
            Log.i(TAG, "Stopping service at ${Date(endTime)}")
            stopSelf()
        }
    }

    private fun startServiceActions() {
        serviceStartTime = System.currentTimeMillis()
        updateNotification()
    }

    private fun updateNotification() {
        serviceScope.launch {
            var counter = 0
            while (isActive) { // Ensures loop stops when the coroutine is cancelled
                val elapsedTime = System.currentTimeMillis() - serviceStartTime
                val minutes = (elapsedTime / (1000 * 60)).toInt()
                val seconds = ((elapsedTime / 1000) % 60).toInt()
                val notificationText = "Service running: $minutes min $seconds sec"
                val notification = createNotification(notificationText)
                notificationManager?.notify(FOREGROUND_SERVICE_ID, notification)

//                 Call API every 5 minutes
                if (counter % 10 == 0) {
                    callApiData()
                }
//

                counter++
//               callApiData()
                delay(30_000) // Update notification every 30 seconds
            }
        }
    }

    private suspend fun callApiData() {

        if (Utils.isInternetAvailable(applicationContext))
        {
            if (locationTrack.checkLocationPermissions())
            {
                val locationTrack = LocationTrack(applicationContext)
                val mLocationManager = applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

                locationTrack.getLocation(mLocationManager) { location ->
                         // Check if location is not null
                    location?.let {
                        callApi(it.latitude, it.longitude)
                        Log.i(TAG, "fetchLocationData: location not null: $it")
                    }
                }
            }else{
                callApi(lat = 0.0, lan = 0.0)
            }
        }else{
            // handle offline case
            callApi(lat = 0.0, lan = 0.0)
        }

    }

    private fun callApi(lat: Double, lan: Double) {
        Log.i(TAG, "callApiData: ")
        var wifiList= listOf<WifiModel>()
        val wifiScanner = WifiScanner(applicationContext)
        wifiScanner.scanWifiNetworks { scanResults ->

            wifiList = if (scanResults.isNotEmpty()) {
                scanResults.map { result ->
                    WifiModel(ssid = result.SSID, bssid = result.BSSID, strength = Utils.rssiToPercentage(result.level) )
                }
            } else{
                arrayListOf()
            }

            val user=SharedPref.getInstance(applicationContext)?.getUser()

            user?.let {itit->

                val randomUid=Utils.generateRandomFourDigitUuid()
                val record=RecordModel(
                    uuid=randomUid,
                    user_id = itit?.id.toString(),
                    lat = lat,
                    lng = lan,
                    localTime = Utils.getCurrent24HourTime(),
                    time = Utils.getCurrentUtcTime(),
                    attendanceType =StatusEnum.default.name,
                    attendanceStatus = Utils.checkInternetAndSetStatus(applicationContext),
                    isForceAttendance = false,
                    isLocation = locationTrack.checkLocationPermissions(),
                    wifiService = wifiScanner.isWifiEnabled(),
                    dataService = Utils.isMobileDataEnabled(applicationContext),
                    notification = Utils.isNotificationPermissionGranted(applicationContext),
                    batterySaver = Utils.isBatterySaverOn(applicationContext),
                    batteryOptimization = Utils.isBatteryOptimizationOff(applicationContext),
                    wifi_list = wifiList
                )

                if (Utils.isInternetAvailable(applicationContext)) {

                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            // Step 1: Fetch all records from the database
                            val records = dao.getAllRecords(itit?.id.toString())

                            // Step 2: Convert each record into a DataRequest model
                            val listDataRequest = records.map { it.toDataRequest() }.toMutableList()
                            Log.i(TAG, "callApiData: listDataRequest from db: ${listDataRequest.size}")

                            // Step 3: Add the new record to the list
                            listDataRequest.add(record.toDataRequest())

                            // Step 4: Log the final list
                            Log.i(TAG, "callApiData: listDataRequest after adding object: ${listDataRequest.size}")

                            // Step 5: Get the auth token
                            val token = SharedPref.getInstance(applicationContext)?.getToken() ?: ""

                            // **Step 6: Switch to Main thread before updating LiveData**
                            val response = callApi(listDataRequest, token)

                            Log.i(TAG, "processRequestFromServer:: dataRequestFromServer apiResponse: $response")

                            if (response.isSuccessful)
                            {
                                val attendaceResponseModel = response.body() as AttendaceResponseModel
                                Log.i(TAG, "processRequestFromServer: successful:${attendaceResponseModel}")

                                attendaceResponseModel.errors?.firstOrNull()?.let {
                                    handleError(it)
                                } ?: handleSuccessfulResponse(record, attendaceResponseModel)
                            } else {
                                handleUnsuccessfulResponse(response)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error fetching records: ${e.message}")
                        }
                    }

                } else {
                    Log.i(TAG, "callApiData: saving to database: ${record}")

                    // Ensure database insertion runs in a background thread
                    CoroutineScope(Dispatchers.IO).launch {
                        dao.insertRecord(record)

                    }
                }

            }?.run {
//                dismissProgressDialog()
                Log.i(TAG, "callApiData: user not found")
            }

        }

    }
    private fun handleSuccessfulResponse(
        record: RecordModel,
        attendaceResponseModel: AttendaceResponseModel,

        ) {
        Log.i(TAG, "handleSuccessfulResponse: ${attendaceResponseModel}")
        attendaceResponseModel.data.forEach { attendance ->
            Log.i(TAG, "setUpObserver: eachResponse:${attendance}")
            when (attendance.attendanceStatus) {
                "offline" -> {
                    // If status is "offline", delete corresponding record from database
                    CoroutineScope(Dispatchers.IO).launch {
                        val uuid = attendance.UUID // Get UUID from response
                        db.dbDao().deleteRecordByUuid(uuid)

                    }
                }
            }
        }
    }


    private fun handleError(error: ErrorDetail) {
        if (error.detail == "LOGOUT" || error.code==401 || error.code==422) {
            CoroutineScope(Dispatchers.IO).launch {

                dao.deleteAllRecords()
                SharedPref.getInstance(applicationContext)?.clearPrefrence()
            }

        } else {
            Log.i(TAG, "processRequests: otherResponse: ${error.detail}")
        }
    }
    private fun handleUnsuccessfulResponse(
        response: Response<AttendaceResponseModel>,
    ) {
        Log.i(TAG, "handleUnsuccessfulResponse: ")
        val errorResponse = response.parseErrorBody()
        errorResponse?.errors?.firstOrNull()?.let {
            if (it.detail == "LOGOUT" || it.code==401 || it.code==422) {
                CoroutineScope(Dispatchers.IO).launch {
                    dao.deleteAllRecords()
                    SharedPref.getInstance(applicationContext)?.clearPrefrence()
                }
            } else {
                Log.i(TAG, "handleUnsuccessfulResponse: not successful: ${it.detail}")
            }
        } ?: run {
            Log.i(TAG, "handleUnsuccessfulResponse:: response not success: Failed with code: ${response.errorBody()?.string()}")
        }
    }

    private suspend fun callApi(data: List<DataRequest>, token: String): Response<AttendaceResponseModel> {
        val response=repository.sendData(data, token)
        Log.i(TAG, "callApi: respobse:${response}\n:${response.code()}")
        return response
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

    override fun onDestroy() {
        super.onDestroy()
        timer?.cancel()
        serviceScope.cancel()
    }

    override fun onBind(p0: Intent?): IBinder? {
        return null
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, getString(R.string.breakfast_notification_channel_id))
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setVibrate(null)
            .build()
    }

    companion object {
        private const val FOREGROUND_SERVICE_ID = 101
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            if (context != null) {
                scheduleDailyService(context)
            }
        }
    }
}
fun scheduleDailyService(context: Context) {
    val user = SharedPref.getInstance(context)?.getUser()
    val timetable = user?.timetable?.range ?: emptyList()
    val currentDay = Utils.getCurrentDay()
    val todaySchedule = timetable.find { it.day == currentDay }
    Log.i("MyForegroundService", "scheduleDailyService: todaySechdule:${todaySchedule}")

    if (todaySchedule?.start == null || todaySchedule.end == null) {
        Log.i("MyForegroundService", "No working hours set for today ($currentDay). Service not scheduled.")
        return
    }

    val startTimeMillis = Utils.convertToMillis(todaySchedule.start) - 3600000 // 1 hour before start
    val endTimeMillis = Utils.convertToMillis(todaySchedule.end) + 3600000     // 1 hour after end
    val currentTimeMillis = System.currentTimeMillis()

    // Case 1: If the user logs in before the scheduled start time → Schedule service
    if (currentTimeMillis < startTimeMillis) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, MyForegroundService::class.java)
        val pendingIntent = PendingIntent.getService(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            startTimeMillis,
            pendingIntent
        )

        Log.i("MyForegroundService", "Service scheduled at: ${Date(startTimeMillis)}")
    }
    // Case 2: If the user logs in within the working hours → Start service immediately
    else if (currentTimeMillis in startTimeMillis..endTimeMillis) {
        Log.i("MyForegroundService", "User logged in late, starting service immediately.")
        context.startForegroundService(Intent(context, MyForegroundService::class.java))
    }
    // Case 3: If the user logs in after the working hours → Do not start service
    else {
        Log.i("MyForegroundService", "User logged in after work hours. Service not started.")
    }
}
