package com.shiftsmart.plus.services

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.shiftsmart.plus.R
import com.shiftsmart.plus.database.DBDao
import com.shiftsmart.plus.database.RecordModel
import com.shiftsmart.plus.database.ShiftSmartPlusDatabase
import com.shiftsmart.plus.enums.StatusEnum
import com.shiftsmart.plus.models.AttendaceResponseModel
import com.shiftsmart.plus.models.DataRequest
import com.shiftsmart.plus.models.TimeRange
import com.shiftsmart.plus.models.UserModel
import com.shiftsmart.plus.models.WifiModel
import com.shiftsmart.plus.periodicAction.AlarmScheduler
import com.shiftsmart.plus.periodicAction.AlarmScheduler.scheduleApiWorker
import com.shiftsmart.plus.periodicAction.WifiScanWorker
import com.shiftsmart.plus.repository.MainRepository
import com.shiftsmart.plus.ui.activities.MainActivity
import com.shiftsmart.plus.utils.SharedPref
import com.shiftsmart.plus.utils.Utils
import com.shiftsmart.plus.utils.Utils.getCalendarForShift
import com.shiftsmart.plus.utils.Utils.getCurrentDayName
import com.shiftsmart.plus.utils.parseErrorBody
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Response
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@AndroidEntryPoint
class MyService : Service() {

    @Inject
    lateinit var repository: MainRepository
    @Inject
    lateinit var track: LocationTrack
    @Inject
    lateinit var db: ShiftSmartPlusDatabase
    private lateinit  var dao: DBDao

    private var notificationManager: NotificationManager? = null

    private  val TAG = "MyService"

    private lateinit var wifiManager: WifiManager
    private val wifiScanResults = mutableListOf<WifiModel>()

    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra("message") ?: return
            updateNotification(message)
        }
    }

    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val wifiResults = intent?.getParcelableArrayListExtra<WifiModel>("wifiResults")
            // Handle Wi-Fi results

            wifiScanResults.clear()
            wifiResults?.let { wifiScanResults.addAll(it) }
            Log.i(TAG, "Received Wi-Fi scan results: $wifiResults")
        }
    }

    private fun registerWifiScanReceiver() {
        val filter = IntentFilter("com.example.WIFI_SCAN_RESULTS")
        LocalBroadcastManager.getInstance(this).registerReceiver(wifiScanReceiver, filter)
    }

    private fun unregisterWifiScanReceiver() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(wifiScanReceiver)
    }

    override fun onCreate() {
        super.onCreate()

        Log.i(TAG, "onCreate: Service is being created")

        // ✅ Initialize database DAO
        dao = db.dbDao()

        // ✅ Initialize FusedLocationProviderClient
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // ✅ Register Wi-Fi scan receiver
        registerWifiScanReceiver()

        // ✅ Create notification channel (but don’t start foreground service yet)
        createNotificationChannel()

        // ✅ Register for local broadcast updates (notification updates, etc.)
        val filter = IntentFilter("UPDATE_NOTIFICATION")
        LocalBroadcastManager.getInstance(applicationContext).registerReceiver(notificationReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: Service restarted")

        addWifiScanner() // Restart Wi-Fi scanning

        // ✅ Ensure `fusedLocationClient` is initialized
        if (!::fusedLocationClient.isInitialized) {
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        }

        startNewService()

        // ✅ Re-register the broadcast receiver for notification updates
        val filter = IntentFilter("UPDATE_NOTIFICATION")
        LocalBroadcastManager.getInstance(applicationContext).registerReceiver(notificationReceiver, filter)

        return START_STICKY // Ensures the service restarts if killed
    }


    private fun addWifiScanner() {
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        // Use WorkManager to perform WiFi scan
        startWifiScanWork()
    }

    @SuppressLint("MissingPermission")
    private fun startWifiScanWork() {

        val wifiScanWorkRequest = OneTimeWorkRequestBuilder<WifiScanWorker>()
            .setInitialDelay(1, TimeUnit.SECONDS) // Optional: Delay before first scan
            .build()

        WorkManager.getInstance(this).enqueue(wifiScanWorkRequest)
    }


    private fun createNotificationChannel() {
        Log.i("MyService", "createNotificationChannel: ")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                getString(R.string.breakfast_notification_channel_id),
                getString(R.string.breakfast_notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            )
            if (notificationManager != null) {
                notificationManager!!.createNotificationChannel(serviceChannel)
            }
        }
    }

    private fun createNotification(message: String): Notification {
        Log.i("MyService", "createNotification: ${Utils.getCurrentDateTime()}-->message:${message}")

        startWifiScanWork()

        if (!message.contains("Data synced to admin panel at", ignoreCase = true) &&
            !message.contains("Data saved in database at", ignoreCase = true) &&
            !message.contains("Data stored at", ignoreCase = true) &&
            !message.contains("Initializing Service", ignoreCase = true)
            ) {
            CoroutineScope(Dispatchers.IO).launch {
                callApiData()
            }
        }

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE // ✅ Fix PendingIntent
        )
        return NotificationCompat.Builder(this, getString(R.string.breakfast_notification_channel_id))
            .setContentTitle("Service Running")
            .setContentText(message)
            .setSmallIcon(R.mipmap.ic_launcher)// ✅ Use valid icon
            .setContentIntent(pendingIntent)
            .build()
    }

    fun updateNotification(message: String) {
        if (notificationManager != null) {
            notificationManager!!.notify(1, createNotification(message))
        }
        Log.i(TAG, "updateNotification: message${message} at ${Utils.getCurrentDateTime()}")


    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i("MyService", "onDestroy: ")
        finishAllData()

    }

    private fun finishAllData() {
        Log.i(TAG, "finishAllData: Attempting to stop the service and all scheduled tasks.")

        // Stop Foreground Service Properly
        stopForeground(true) // Removes the notification
        stopSelf() // Stops the service

        // Unregister Receivers
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(notificationReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Notification receiver was not registered or already unregistered.")
        }

        unregisterWifiScanReceiver()

////        // Cancel All WorkManager Tasks
//        WorkManager.getInstance(this).cancelAllWorkByTag(WORK_TAG)
//        WorkManager.getInstance(this).cancelUniqueWork("API_WORK") // Ensure unique work is stopped
//
//        // Cancel All Scheduled Alarms
//        AlarmScheduler.cancelAlarms(this)


        val user = SharedPref.getInstance(applicationContext)?.getUser()
        Log.i(TAG, "startMyService: Retrieved user info = $user")

        if (user != null && user.isActive == true) {
            // Re-fetch shift data and reschedule alarms
            user.timetable?.range?.let {
                AlarmScheduler.scheduleAlarms(applicationContext, it)
                Log.i(TAG, "startMyService: Alarms scheduled with range = ${it}")
            }
        }


        // Clear Notifications
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancelAll()

        Log.i(TAG, "finishAllData: Service and tasks should now be completely stopped.")
    }


    override fun onBind(intent: Intent): IBinder? {
        return null
    }


    companion object {
        private const val WORK_TAG = "ApiCallWorker"
    }


    private var apiCallInProgress = false // Prevent multiple API calls
    private var locationReceived = false  // Track if we received location update

    private suspend fun callApiData() {
//        Log.i(TAG, "callApiData: at ${Utils.getCurrentDateTime()}")
        try {
            if (ActivityCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
            ) {

                CoroutineScope(Dispatchers.Main).launch {

                    val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                    locationReceived = false // Reset before starting location request

                    val locationListener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            if (!locationReceived) {
                                locationReceived = true
                                locationManager.removeUpdates(this) // Stop further updates

                                Log.i(TAG, "callApiData: Fresh location received: ${location.latitude}, ${location.longitude} at ${Utils.getCurrentDateTime()}")
                                makeApiCallOnce(location.latitude, location.longitude)

                            }
                        }

                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                        override fun onProviderEnabled(provider: String) {}
                        override fun onProviderDisabled(provider: String) {}
                    }

                    // Request fresh location updates
//                    locationManager.requestLocationUpdates(
//                        LocationManager.GPS_PROVIDER,
//                        0L, 0f, locationListener
//                    )

                    locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        0L, 0f, locationListener
                    )

                    // Wait for location update, if not received in 10 seconds, use last known location
                    CoroutineScope(Dispatchers.IO).launch {
                        delay(10000) // Wait for 10 seconds
                        if (!locationReceived) {
                            val lastKnownLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

                            if (lastKnownLocation != null) {
                                Log.i(TAG, "callApiData: Using last known location: ${lastKnownLocation.latitude}, ${lastKnownLocation.longitude}  at ${Utils.getCurrentDateTime()}")
                                makeApiCallOnce(lastKnownLocation.latitude, lastKnownLocation.longitude)
                            } else {
                                Log.i(TAG, "callApiData:No location available. Using default values.  at ${Utils.getCurrentDateTime()}")
                                makeApiCallOnce(0.0, 0.0) // Default if no location is available
                            }
                        }
                    }
                }
            }
            else {
                Log.i(TAG, "callApiData:Permissions not granted")
                makeApiCallOnce(0.0, 0.0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "callApiData: exception", e)
            makeApiCallOnce(0.0, 0.0)
        }
    }

    // Function to make API call only once
    @SuppressLint("SuspiciousIndentation")
    private fun makeApiCallOnce(lat: Double, lon: Double) {

        val user = SharedPref.getInstance(applicationContext)?.getUser()
        user?.let {it1->
            val record = RecordModel(
                uuid = Utils.generateRandomFourDigitUuid(),
                user_id = user?.id.toString(),
                lat = lat,
                lng = lon,
                localTime = Utils.getCurrent24HourTime(),
                time = Utils.getCurrentUtcTime(),
                attendanceType = StatusEnum.default.name,
                attendanceStatus = Utils.checkInternetAndSetStatus(applicationContext),
                isForceAttendance = false,
                isLocation = track.checkLocationPermissions(),
                wifiService =wifiManager.isWifiEnabled(),
                dataService = Utils.isMobileDataEnabled(applicationContext),
                notification = Utils.isNotificationPermissionGranted(applicationContext),
                batterySaver = !Utils.isBatterySaverOn(applicationContext),
                batteryOptimization = !Utils.isBatteryOptimizationOff(applicationContext),
                wifi_list = wifiScanResults
            )
            CoroutineScope(Dispatchers.IO).launch {
                it1.timetable?.range?.let { saveDataLocally(record, it) }

                callApi(lat, lon,record,user)
                delay(20000) // Reset flag after 20 seconds
                apiCallInProgress = false
            }
        }

    }

    @SuppressLint("MissingPermission")
    private suspend fun callApi(lat: Double, lan: Double, record: RecordModel, user: UserModel?) {
        Log.i(TAG, "MRcallApi: at ${Utils.getCurrentDateTime()} with location:${lat},${lan}")

        try
        {
             // Start Wi-Fi scan service
            Log.i(TAG, "MRcallApi: model:${record}")
//             Handle the data and API call based on internet availability
            if (Utils.isInternetAvailable(applicationContext)) {
                val records = dao.getAllRecords(user?.id.toString()).map { it.toDataRequest() }.toMutableList()
//                records.add(record.toDataRequest())
                Log.i(TAG, "MRcallApi: networkAvailable recordModel:${records}")

                val token = SharedPref.getInstance(applicationContext)?.getToken() ?: ""
                CoroutineScope(Dispatchers.IO).launch {
                    val response = callServerApi(records, token)
                    if (response.isSuccessful) {
                        val attendanceResponse = response.body() as AttendaceResponseModel

                        if (attendanceResponse.data.isNotEmpty())
                        {
                            if (attendanceResponse.data[0].status == "error")
                            {
//                           handleSuccessfulResponse(record, response.body())
                                finishAllData()
                                return@launch
                            }

                            Log.i(TAG, "MRcallApi: record successfully sent to admin panal")
                            handleSuccessfulResponse(record, response.body())
                            sendNotificationUpdate("Data synced to admin panel at ${Utils.getCurrentDateTime()}")
                        }

                        Log.i(TAG, "MRcallApi: attendacneResponse:${attendanceResponse}")

                    } else {
                        Log.i(TAG, "MRcallApi: api calls failed error:${response}")
                        handleUnsuccessfulResponse(record,response)
                    }
                }
            }
            else {
                Log.i(TAG, "MRcallApi: network not available recordModel:${record}")
            }
        }
        catch (e:Exception)
        {
            Log.i(TAG, "MRcallApi: exception:${e.printStackTrace()}")
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
            dao.deleteRecordByUuid(attendance.UUID)
        }
    }

    private suspend fun handleUnsuccessfulResponse(
        record: RecordModel,
        response: Response<AttendaceResponseModel>
    ) {

        response.errorBody()?.let {
            val errorResponse = response.parseErrorBody()
            Log.i(TAG, "handleUnsuccessfulResponse: response errorbody:${errorResponse}")

            errorResponse?.errors?.firstOrNull()?.let { error ->
                if (error.detail == "LOGOUT" || error.code in listOf(401, 422,500)) {
                    withContext(Dispatchers.IO) {
//                        dao.deleteAllRecords()
                        SharedPref.getInstance(applicationContext)?.clearPrefrence()
                    }
                }

            }?.run {
                Log.i(TAG, "handleUnsuccessfulResponse: response run error:${response}")
            }
        }
    }
    private fun sendNotificationUpdate(message: String) {
        Log.i(TAG, "sendNotificationUpdate: on${Utils.getCurrentDateTime()}")
        val intent = Intent("UPDATE_NOTIFICATION")
        intent.putExtra("message", message)
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
    }

/////////////////////////////////////////////////


    fun startNewService() {

        val user=SharedPref.getInstance(applicationContext)?.getUser()

        Log.i(TAG, "startNewService: Retrieved at ${Utils.getCurrentDateTime()}")

        if (user != null && user.isActive == true) {
            // Re-fetch shift data and reschedule alarms
            user.timetable?.range?.let {

                val today = getCurrentDayName() // Get today's name, e.g., "Tuesday"
                Log.i("TAG", "startNewService: Today's Day: $today")

                val todayShift = it.find { it.day.equals(today, ignoreCase = true) }

                if (todayShift != null && todayShift.start != null && todayShift.end != null) {
                    Log.i(TAG, "startNewService:Today's Shift -> day:${todayShift.day}, start:${todayShift.start}, end:${todayShift.end}")

                    val startCalendar = getCalendarForShift(todayShift.day, todayShift.start, -1)
                    val endCalendar = getCalendarForShift(todayShift.day, todayShift.end, 1)

                    Log.i(TAG, "startNewService:startCalendar: ${startCalendar?.time} --> endCalendar: ${endCalendar?.time}")

                    val currentTime = Calendar.getInstance()

                    if (startCalendar != null && endCalendar != null) {

                        // ✅ Schedule API Worker ONLY IF current time is between shift start & end
                        if (currentTime.after(startCalendar) && currentTime.before(endCalendar)) {
                            Log.i(TAG, "startNewService:Current time is within shift period, scheduling API Worker.")
                            // ✅ Ensure notification channel & foreground service starts
                            scheduleApiWorker(applicationContext)
                            CoroutineScope(Dispatchers.Main).launch {
                                createNotificationChannel()
                                notificationManager = getSystemService(NotificationManager::class.java)
                                startForeground(1, createNotification("Service Restarted")) // Restart foreground service
                            }
                        } else {
                            scheduleService(applicationContext, startCalendar, true)
                            scheduleService(applicationContext, endCalendar, false)
                            Log.i(TAG, "startNewService:Current time is outside shift period, NOT scheduling API Worker.")
                        }
                    } else {

                    }
                } else {
                    Log.i(TAG, "startNewService:No shift found for today.")
                }
            }
        }
    }
    private fun scheduleService(context: Context, calendar: Calendar, isStart: Boolean) {
        Log.i(TAG, "scheduleServiceService:Scheduling Service at ${calendar.time}, isStart: $isStart")

        try {
            val intent = Intent(context, MyService::class.java).apply {
                action = if (isStart) "START_SERVICE" else "STOP_SERVICE"
            }
            val requestCode = if (isStart) 1001 else 1002
            val pendingIntent = PendingIntent.getService(
                context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE
            )

//            val pendingIntent = PendingIntent.getService(
//                context,
//                calendar[Calendar.DAY_OF_YEAR] + (if (isStart) 0 else 1),
//                intent,
//                PendingIntent.FLAG_IMMUTABLE
//            )

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            if (!alarmManager.canScheduleExactAlarms()) {
                Log.e(TAG, "scheduleServiceService:Device does not allow exact alarms! Request permission.")
            }

            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)

            Log.i(TAG, "scheduleServiceService:Scheduled ${if (isStart) "start" else "stop"} service at: ${calendar.time}")
            // ✅ Ensure device wakes up for the alarm (Important!)

        } catch (e: Exception) {
            Log.e(TAG, "scheduleServiceService:Error scheduling service", e)
        }
    }

    fun saveDataLocally(record: RecordModel, shifts: List<TimeRange>) {
        Log.i(TAG, "saveDataLocally:scheduleAlarms: ${Utils.getCurrentDateTime()}")

        val today = getCurrentDayName() // Get today's name, e.g., "Tuesday"
        Log.i(TAG, "saveDataLocally:Today's Day: $today")

        val todayShift = shifts.find { it.day.equals(today, ignoreCase = true) }

        if (todayShift != null && todayShift.start != null && todayShift.end != null) {
            Log.i(TAG, "saveDataLocally:Today's Shift -> day:${todayShift.day}, start:${todayShift.start}, end:${todayShift.end}")

            val startCalendar = getCalendarForShift(todayShift.day, todayShift.start, -1)
            val endCalendar = getCalendarForShift(todayShift.day, todayShift.end, 1)

            Log.i(TAG, "saveDataLocally:startCalendar: ${startCalendar?.time} --> endCalendar: ${endCalendar?.time}")

            val currentTime = Calendar.getInstance()

            if (startCalendar != null && endCalendar != null) {

                // ✅ Schedule API Worker ONLY IF current time is between shift start & end
                if (currentTime.after(startCalendar) && currentTime.before(endCalendar)) {
                    Log.i(TAG, "saveDataLocally:Current time is within shift period, scheduling API Worker.")
                    dao.insertRecord(record)
                    sendNotificationUpdate("Data stored at ${Utils.getCurrentDateTime()}")
                } else {
                    finishAllData()
                    Log.i(TAG, "saveDataLocally: Current time is outside shift period, NOT scheduling API Worker.")
                }
            }
        } else {
            Log.i(TAG, "saveDataLocally:No shift found for today.")
        }
    }

}
