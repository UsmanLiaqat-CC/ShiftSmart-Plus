package com.shiftsmart.plus.services

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.shiftsmart.plus.R
import com.shiftsmart.plus.database.DBDao
import com.shiftsmart.plus.database.DbConstants.RECORD_INTERVAL
import com.shiftsmart.plus.database.RecordModel
import com.shiftsmart.plus.database.ShiftSmartPlusDatabase
import com.shiftsmart.plus.enums.StatusEnum
import com.shiftsmart.plus.models.AttendaceResponseModel
import com.shiftsmart.plus.models.DataRequest
import com.shiftsmart.plus.models.TimeRange
import com.shiftsmart.plus.models.UserModel
import com.shiftsmart.plus.models.WifiModel
import com.shiftsmart.plus.periodicAction.AlarmScheduler
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import retrofit2.Response
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.coroutines.resume

/*@AndroidEntryPoint
class MyService : Service() {

    @Inject
    lateinit var repository: MainRepository

    @Inject
    lateinit var track: LocationTrack

    @Inject
    lateinit var db: ShiftSmartPlusDatabase
    private lateinit var dao: DBDao

    private var notificationManager: NotificationManager? = null

    private var isTimeReciverDataComes = false

    private val TAG = "MyService"
    private var timeChangeReceiver: TimeChangeReceiver? = null
    private var isReceiverRegistered = false
//    private val wifiScanResults = mutableListOf<WifiModel>()
    private var isNotificationReceiverRegistered = false

    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private lateinit var wifiManager: WifiManager
    private val wifiScanResults = mutableListOf<WifiModel>()
    private var isWifiReceiverRegistered = false

    private val wifiScanReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            val success = intent?.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false) ?: false
            if (success) {
                val results = wifiManager.scanResults
                wifiScanResults.clear()
                wifiScanResults.addAll(results.map { WifiModel(it.SSID, it.BSSID, it.level) })
                Log.i("WiFiScan", "Results: $wifiScanResults")
            }
        }
    }

    // ✅ Register Wi-Fi scan receiver safely
    private fun registerWifiScanReceiver() {
        if (!isWifiReceiverRegistered) {
            registerReceiver(wifiScanReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
            isWifiReceiverRegistered = true
            Log.i(TAG, "Wi-Fi scan receiver registered.")
        } else {
            Log.w(TAG, "Wi-Fi scan receiver already registered.")
        }
    }


    // ✅ Unregister Wi-Fi scan receiver safely
    private fun unregisterWifiScanReceiver() {
        if (isWifiReceiverRegistered) {
            try {
                unregisterReceiver(wifiScanReceiver)
                isWifiReceiverRegistered = false
                Log.i(TAG, "Wi-Fi scan receiver unregistered.")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Wi-Fi scan receiver was already unregistered.")
            }
        } else {
            Log.w(TAG, "Wi-Fi scan receiver was never registered, skipping unregistration.")
        }
    }

    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra("message") ?: return
            updateNotification(message)
        }
    }

    private lateinit var wakeLock: PowerManager.WakeLock
    private val handler = Handler(Looper.getMainLooper())

    private val wakeRunnable = object : Runnable {
        override fun run() {
            wakeScreen()
            handler.postDelayed(this, 1*60 * 1000) // Run every 1 minute
        }
    }


    override fun onCreate() {
        super.onCreate()

        Log.i(TAG, "onCreate: Service is being created")

        // ✅ Initialize database DAO
        dao = db.dbDao()

        registerTimeReceiver()

        acquireWakeLock()
        // ✅ Initialize FusedLocationProviderClient
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        // ✅ Register Wi-Fi scan receiver
        registerWifiScanReceiver()

        // ✅ Create notification channel (but don’t start foreground service yet)
        createNotificationChannel()

        handler.post(wakeRunnable) // Start wake-up loop

        if (!isNotificationReceiverRegistered) {
            val filter = IntentFilter("UPDATE_NOTIFICATION")
            LocalBroadcastManager.getInstance(applicationContext)
                .registerReceiver(notificationReceiver, filter)
            isNotificationReceiverRegistered = true
        }
    }

    private fun registerTimeReceiver() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter(Intent.ACTION_TIME_TICK)
            timeChangeReceiver = TimeChangeReceiver()
            registerReceiver(timeChangeReceiver, filter)
            isReceiverRegistered = true
            Log.i("TimeChangeReceiver", "Receiver Registered Successfully")
        } else {
            Log.i("TimeChangeReceiver", "Receiver Already Registered. Skipping.")
        }
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: Service restarted")

        addWifiScanner() // Restart Wi-Fi scanning

        // ✅ Ensure `fusedLocationClient` is initialized
        if (!::fusedLocationClient.isInitialized) {
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        }

        startNewService()
        // Start periodic API task if not already running

//        // ✅ Re-register the broadcast receiver for notification updates
        if (!isNotificationReceiverRegistered) {
            val filter = IntentFilter("UPDATE_NOTIFICATION")
            LocalBroadcastManager.getInstance(applicationContext)
                .registerReceiver(notificationReceiver, filter)
            isNotificationReceiverRegistered = true
        }

        return START_STICKY // Ensures the service restarts if killed
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "MyApp:WakeLock"
        )
    }

    private fun releaseWakeLock() {
        if (wakeLock.isHeld) wakeLock.release()
    }

    private fun wakeScreen() {
        if (!wakeLock.isHeld) {
            wakeLock.acquire(5000) // Wake up screen for 5 seconds
        }
    }

    private fun addWifiScanner() {
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiManager.startScan()
        // Use WorkManager to perform WiFi scan
//        startWifiScanWork()
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
                NotificationManager.IMPORTANCE_HIGH // Make sure importance is high for heads-up & lock screen
            ).apply {
                lockscreenVisibility =
                    Notification.VISIBILITY_PUBLIC // ✅ This makes it fully visible on lock screen
            }

            notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager?.createNotificationChannel(serviceChannel)
        }
    }


    private fun createNotification(message: String): Notification {
        Log.i("MyService", "createNotification: ${Utils.getCurrentDateTime()}-->message:${message}")

        startWifiScanWork()
//
//        if (!message.contains("Data synced to admin panel at", ignoreCase = true) &&
//            !message.contains("Data saved in database at", ignoreCase = true) &&
//            !message.contains("Data stored at", ignoreCase = true) &&
//            !message.contains("Service Restarted", ignoreCase = true)
//        ) {
//            CoroutineScope(Dispatchers.IO).launch {
//                callApiData()
//            }
//        }

        if (message=="Update Data")
        {
            CoroutineScope(Dispatchers.IO).launch {
                callApiData()
            }
        }

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE // ✅ Fix PendingIntent
        )
        return NotificationCompat.Builder(
            this,
            getString(R.string.breakfast_notification_channel_id)
        )
            .setContentTitle("Service Running")
            .setContentText(message)
            .setSmallIcon(R.mipmap.ic_launcher)// ✅ Use valid icon
            .setContentIntent(pendingIntent)
            .build()
    }

    fun updateNotification(message: String) {

        isTimeReciverDataComes = message=="Update Data"

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
    private fun unregisterTimeReceiver() {
        if (isReceiverRegistered && timeChangeReceiver != null) {
            unregisterReceiver(timeChangeReceiver)
            isReceiverRegistered = false
            Log.i("TimeChangeReceiver", "Receiver Unregistered Successfully")
        }
    }
    private fun finishAllData() {
        Log.i(TAG, "finishAllData: Attempting to stop the service and all scheduled tasks.")
        SharedPref.getInstance(applicationContext)?.saveLastApiCallTime(0L)
        // Stop Foreground Service Properly
        stopForeground(true) // Removes the notification
        stopSelf() // Stops the service
        releaseWakeLock()
        handler.removeCallbacks(wakeRunnable)


        if (isNotificationReceiverRegistered) {
            LocalBroadcastManager.getInstance(applicationContext).unregisterReceiver(notificationReceiver)
            isNotificationReceiverRegistered = false
        }

        unregisterTimeReceiver()

        unregisterWifiScanReceiver()

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
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
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
            if (ActivityCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {

                CoroutineScope(Dispatchers.Main).launch {

                    val locationManager =
                        getSystemService(Context.LOCATION_SERVICE) as LocationManager
                    locationReceived = false // Reset before starting location request

                    val locationListener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            if (!locationReceived) {
                                locationReceived = true
                                locationManager.removeUpdates(this) // Stop further updates

                                Log.i(
                                    TAG,
                                    "callApiData: Fresh location received: ${location.latitude}, ${location.longitude} at ${Utils.getCurrentDateTime()}"
                                )
                                makeApiCallOnce(location.latitude, location.longitude)

                            }
                        }

                        override fun onStatusChanged(
                            provider: String?,
                            status: Int,
                            extras: Bundle?
                        ) {
                        }

                        override fun onProviderEnabled(provider: String) {}
                        override fun onProviderDisabled(provider: String) {}
                    }


                    locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        0L, 0f, locationListener
                    )

                    // Wait for location update, if not received in 10 seconds, use last known location
                    CoroutineScope(Dispatchers.IO).launch {
                        delay(10000) // Wait for 10 seconds
                        if (!locationReceived) {
                            val lastKnownLocation =
                                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                                    ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

                            if (lastKnownLocation != null) {
                                Log.i(
                                    TAG,
                                    "callApiData: Using last known location: ${lastKnownLocation.latitude}, ${lastKnownLocation.longitude}  at ${Utils.getCurrentDateTime()}"
                                )
                                makeApiCallOnce(
                                    lastKnownLocation.latitude,
                                    lastKnownLocation.longitude
                                )
                            } else {
                                Log.i(
                                    TAG,
                                    "callApiData:No location available. Using default values.  at ${Utils.getCurrentDateTime()}"
                                )
                                makeApiCallOnce(0.0, 0.0) // Default if no location is available
                            }
                        }
                    }
                }
            } else {
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
        user?.let { it1 ->
            val record = RecordModel(
                uuid = Utils.generateRandomFourDigitUuid(),
                user_id = user?._id.toString(),
                lat = lat,
                lng = lon,
                localTime = Utils.getCurrent24HourTime(),
                time = Utils.getCurrentUtcTime(),
                attendanceType = StatusEnum.default.name,
                attendanceStatus = Utils.checkInternetAndSetStatus(applicationContext),
                isForceAttendance = false,
                isLocation = track.checkLocationPermissions(),
                wifiService = wifiManager.isWifiEnabled(),
                dataService = Utils.isMobileDataEnabled(applicationContext),
                notification = Utils.isNotificationPermissionGranted(applicationContext),
                batterySaver = !Utils.isBatterySaverOn(applicationContext),
                batteryOptimization = !Utils.isBatteryOptimizationOff(applicationContext),
                wifi_list = wifiScanResults
            )
            CoroutineScope(Dispatchers.IO).launch {
                it1.timetable?.range?.let { saveDataLocally(record, it) }
                callApi(lat, lon, record, user)
             *//*   if (isTimeReciverDataComes)
                {
                    callApi(lat, lon, record, user)
                }*//*

                delay(20000) // Reset flag after 20 seconds
                apiCallInProgress = false
            }
        }

    }

    @SuppressLint("MissingPermission")
    private suspend fun callApi(lat: Double, lan: Double, record: RecordModel, user: UserModel?) {
        Log.i(TAG, "MRcallApi: at ${Utils.getCurrentDateTime()} with location:${lat},${lan}")

        try {
            // Start Wi-Fi scan service
            Log.i(TAG, "MRcallApi: model:${record}")
//             Handle the data and API call based on internet availability
            if (Utils.isInternetAvailable(applicationContext)) {
                val records = dao.getAllRecords(user?._id.toString()).map { it.toDataRequest() }
                    .toMutableList()
//                records.add(record.toDataRequest())
                Log.i(TAG, "MRcallApi: networkAvailable recordModel:${records}")

                val token = SharedPref.getInstance(applicationContext)?.getToken() ?: ""
                CoroutineScope(Dispatchers.IO).launch {
                    val response = callServerApi(records, token)
                    if (response.isSuccessful) {
                        val attendanceResponse = response.body() as AttendaceResponseModel

                        if (attendanceResponse.data.isNotEmpty()) {
                            Log.i(
                                TAG,
                                "MRcallApi:attendaceResponse:${attendanceResponse.toString()}"
                            )


                            if (attendanceResponse.data[0].status.contains("You do not have access to this store today")) {
                                sendNotificationUpdate("You do not have access to this store today")
                                return@launch
                            }
                            handleSuccessfulResponse(record, response.body())

                            Log.i(TAG, "MRcallApi: record successfully sent to admin panal")
                            sendNotificationUpdate("Data synced to admin panel at ${Utils.getCurrentDateTime()}")
                        }

                        Log.i(TAG, "MRcallApi: attendacneResponse:${attendanceResponse}")

                    } else {
                        Log.i(TAG, "MRcallApi: api calls failed error:${response}")
                        handleUnsuccessfulResponse(record, response)
                    }
                }
            } else {
                Log.i(TAG, "MRcallApi: network not available recordModel:${record}")
            }
        } catch (e: Exception) {
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

    private suspend fun callServerApi(data: List<DataRequest>, token: String) =
        repository.sendData(data, token)

    private suspend fun handleSuccessfulResponse(
        record: RecordModel,
        response: AttendaceResponseModel?
    ) {
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
                if (error.detail == "LOGOUT" || error.code in listOf(401, 422, 500)) {
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

        val user = SharedPref.getInstance(applicationContext)?.getUser()

        Log.i(TAG, "startNewService: Retrieved at ${Utils.getCurrentDateTime()}")

        if (user != null && user.isActive == true) {
            // Re-fetch shift data and reschedule alarms
            user.timetable?.range?.let {

                val today = getCurrentDayName() // Get today's name, e.g., "Tuesday"
                Log.i("TAG", "startNewService: Today's Day: $today")

                val todayShift = it.find { it.day.equals(today, ignoreCase = true) }

                if (todayShift != null && todayShift.start != null && todayShift.end != null) {
                    Log.i(
                        TAG,
                        "startNewService:Today's Shift -> day:${todayShift.day}, start:${todayShift.start}, end:${todayShift.end}"
                    )

                    val startCalendar = getCalendarForShift(todayShift.day, todayShift.start, -1)
                    val endCalendar = getCalendarForShift(todayShift.day, todayShift.end, 1)

                    Log.i(
                        TAG,
                        "startNewService:startCalendar: ${startCalendar?.time} --> endCalendar: ${endCalendar?.time}"
                    )

                    val currentTime = Calendar.getInstance()

                    if (startCalendar != null && endCalendar != null) {

                        // ✅ Schedule API Worker ONLY IF current time is between shift start & end
                        if (currentTime.after(startCalendar) && currentTime.before(endCalendar)) {
                            Log.i(
                                TAG,
                                "startNewService:Current time is within shift period, scheduling API Worker."
                            )

                        } else {
                            scheduleService(applicationContext, startCalendar, true)
                            scheduleService(applicationContext, endCalendar, false)
                            Log.i(
                                TAG,
                                "startNewService:Current time is outside shift period, NOT scheduling API Worker."
                            )
                            SharedPref.getInstance(applicationContext)?.saveLastApiCallTime(0L)
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
        Log.i(
            TAG,
            "scheduleServiceService:Scheduling Service at ${calendar.time}, isStart: $isStart"
        )
        try {
            val intent = Intent(context, MyService::class.java).apply {
                action = if (isStart) "START_SERVICE" else "STOP_SERVICE"
            }
            val requestCode = if (isStart) 1001 else 1002
            val pendingIntent = PendingIntent.getService(
                context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE
            )

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                calendar.timeInMillis,
                pendingIntent
            )

            Log.i(
                TAG,
                "scheduleServiceService:Scheduled ${if (isStart) "start" else "stop"} service at: ${calendar.time}"
            )
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
            Log.i(
                TAG,
                "saveDataLocally:Today's Shift -> day:${todayShift.day}, start:${todayShift.start}, end:${todayShift.end}"
            )

            val startCalendar = getCalendarForShift(todayShift.day, todayShift.start, -1)
            val endCalendar = getCalendarForShift(todayShift.day, todayShift.end, 1)

            Log.i(
                TAG,
                "saveDataLocally:startCalendar: ${startCalendar?.time} --> endCalendar: ${endCalendar?.time}"
            )

            val currentTime = Calendar.getInstance()

            if (startCalendar != null && endCalendar != null) {

                // ✅ Schedule API Worker ONLY IF current time is between shift start & end
                if (currentTime.after(startCalendar) && currentTime.before(endCalendar)) {
                    Log.i(
                        TAG,
                        "saveDataLocally:Current time is within shift period, scheduling API Worker."
                    )
                    dao.insertRecord(record)
                    sendNotificationUpdate("Data stored at ${Utils.getCurrentDateTime()}")
                } else {
                    finishAllData()
                    Log.i(
                        TAG,
                        "saveDataLocally: Current time is outside shift period, NOT scheduling API Worker."
                    )
                }
            }
        } else {
            Log.i(TAG, "saveDataLocally:No shift found for today.")
        }
    }

}*/




@AndroidEntryPoint
class MyService : Service() {

    @Inject
    lateinit var repository: MainRepository
    @Inject
    lateinit var track: LocationTrack
    @Inject
    lateinit var db: ShiftSmartPlusDatabase

    private lateinit var dao: DBDao
    private var notificationManager: NotificationManager? = null
    private var isTimeReceiverDataComes = false
    private val TAG = "MyService"

    // Broadcast receivers
    private var isNotificationReceiverRegistered = false
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var wifiManager: WifiManager
    private val wifiScanResults = mutableListOf<WifiModel>()
    private var isWifiReceiverRegistered = false

    // Periodic check variables
    private val handler = Handler(Looper.getMainLooper())
    private val checkInterval = RECORD_INTERVAL * 60 * 1000L // 5 minutes

    // Wake lock
    private lateinit var wakeLock: PowerManager.WakeLock
    private var isServiceRunning = false

    private val periodicCheckRunnable = object : Runnable {
        override fun run() {
            performPeriodicCheck()
            handler.postDelayed(this, checkInterval)
        }
    }

    // Broadcast Receivers
    private val wifiScanReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false) == true) {
                wifiScanResults.clear()
                wifiScanResults.addAll(wifiManager.scanResults.map { WifiModel(it.SSID, it.BSSID, it.level) })
            }
        }
    }

    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getStringExtra("message")?.let { updateNotification(it) }
        }
    }

    // State variables
    private var apiCallInProgress = false
    private var locationReceived = false

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
        isServiceRunning = true

        // Acquire wake lock with proper flags
        acquireWakeLock()

        initializeComponents()
        registerReceivers()
        startPeriodicChecks()
        checkBatteryOptimizations()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
            "MyApp::MyServiceWakelock"
        ).apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 1000L /*10 minutes*/)
        }
    }

    private fun initializeComponents() {
        dao = db.dbDao()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        createNotificationChannel()
    }

    private fun registerReceivers() {
        registerWifiScanReceiver()
        registerNotificationReceiver()
    }

    private fun startPeriodicChecks() {
        // Start handler-based checking as primary
        handler.post(periodicCheckRunnable)

        // Setup AlarmManager as backup with most reliable method
        setupAlarmManagerBackup()

        // Setup JobScheduler as additional backup for newer devices
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            setupJobSchedulerBackup()
        }
    }

    private fun setupAlarmManagerBackup() {
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, AlarmReceiver::class.java).apply {
            action = "PERIODIC_CHECK"
            putExtra("source", "alarm_manager")
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerAt = System.currentTimeMillis() + checkInterval

        // Use setAlarmClock() for maximum reliability (shows in status bar)
        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(triggerAt, pendingIntent),
            pendingIntent
        )
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun setupJobSchedulerBackup() {
        val componentName = ComponentName(this, MyJobService::class.java)
        val jobInfo = JobInfo.Builder(JOB_ID, componentName)
            .setMinimumLatency(checkInterval) // Run after delay
            .setOverrideDeadline(checkInterval + 60_000) // Max 1 minute late
            .setPersisted(true) // Survive reboots
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setRequiresDeviceIdle(false) // Work in Doze mode
            .setRequiresCharging(false)
            .setRequiresBatteryNotLow(false)
            .build()

        val jobScheduler = getSystemService(JobScheduler::class.java)
        jobScheduler.schedule(jobInfo)
    }

    private fun checkBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                handleDeviceSpecificOptimizations()
            }
        }
    }

    private fun handleDeviceSpecificOptimizations() {
        when {
            Build.MANUFACTURER.equals("xiaomi", ignoreCase = true) -> handleXiaomiOptimization()
            Build.MANUFACTURER.equals("samsung", ignoreCase = true) -> handleSamsungOptimization()
            Build.MANUFACTURER.equals("huawei", ignoreCase = true) -> handleHuaweiOptimization()
            Build.MANUFACTURER.equals("oppo", ignoreCase = true) -> handleOppoOptimization()
            Build.MANUFACTURER.equals("vivo", ignoreCase = true) -> handleVivoOptimization()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> requestIgnoreBatteryOptimizations()
            else -> Log.d(TAG, "No special optimization needed for ${Build.MANUFACTURER}")
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun requestIgnoreBatteryOptimizations() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request ignore battery optimizations", e)
        }
    }

    private fun handleXiaomiOptimization() {
        try {
            // Try the new MIUI 12+ method first
            val intent = Intent("miui.intent.action.APP_AUTOSTART_MANAGE").apply {
                setPackage("com.miui.securitycenter")
                putExtra("package_name", packageName)
                putExtra("package_label", getString(R.string.app_name))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            try {
                // Fallback to old method
                val intent = Intent().apply {
                    component = ComponentName(
                        "com.miui.powerkeeper",
                        "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
                    )
                    putExtra("package_name", packageName)
                    putExtra("package_label", getString(R.string.app_name))
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Xiaomi optimization failed", e)
                requestIgnoreBatteryOptimizations()
            }
        }
    }

    private fun handleHuaweiOptimization() {
        try {
            // Try multiple possible Huawei activities
            val intents = listOf(
                Intent().apply {
                    component = ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.optimize.process.ProtectActivity"
                    )
                    putExtra("package_name", packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                Intent().apply {
                    component = ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )

            var success = false
            for (intent in intents) {
                try {
                    startActivity(intent)
                    success = true
                    break
                } catch (e: Exception) {
                    continue
                }
            }

            if (!success) {
                requestIgnoreBatteryOptimizations()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Huawei optimization failed", e)
            requestIgnoreBatteryOptimizations()
        }
    }

    // Similar optimized handlers for Oppo, Vivo, Samsung...

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Service command received")

        when (intent?.action) {
            "START_SERVICE" -> handleServiceStart()
            "STOP_SERVICE" -> handleServiceStop()
            "RESTART_SERVICE" -> {
                if (intent.getBooleanExtra("from_job_service", false)) {
                    Log.d(TAG, "Restart triggered by JobService")
                }
                handleServiceRestart()
            }
            else -> handleRegularStart()
        }

        return START_STICKY
    }

    private fun handleServiceStart() {
        Log.i(TAG, "Starting service")
        startForegroundService()
    }

    private fun handleServiceStop() {
        Log.i(TAG, "Stopping service")
        finishAllData()
    }

    private fun handleServiceRestart() {
        Log.i(TAG, "Restarting service")
        if (!isServiceRunning) {
            startForegroundService()
            startWifiScanning()
            checkShiftAndScheduleTasks()
        }
    }

    private fun handleRegularStart() {
        startForegroundService()
        startWifiScanning()
        checkShiftAndScheduleTasks()
    }

    private fun startForegroundService() {
        val notification = createNotification("Service running").apply {
            flags = flags or Notification.FLAG_NO_CLEAR
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceTypes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                FOREGROUND_SERVICE_TYPE_LOCATION or
                        FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                        FOREGROUND_SERVICE_TYPE_MANIFEST
            } else {
                FOREGROUND_SERVICE_TYPE_LOCATION
            }
            startForeground(NOTIFICATION_ID, notification, serviceTypes)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // Rest of your existing methods (performPeriodicCheck, createNotification, etc.) remain the same
    // but use the improved wake lock and scheduling mechanisms

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        try {
            // Schedule restart using most reliable method
            setupAlarmManagerBackup()

            // Also use JobScheduler as backup
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                setupJobSchedulerBackup()
            }

            // Release resources
            releaseWakeLock()
            unregisterReceivers()

            // Send restart broadcast
            val restartIntent = Intent("RESTART_SERVICE").apply {
                `package` = packageName
            }
            sendBroadcast(restartIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error during onDestroy", e)
        }
    }

    override fun onBind(p0: Intent?): IBinder? {
        return null
    }

    private fun releaseWakeLock() {
        if (::wakeLock.isInitialized && wakeLock.isHeld) {
            wakeLock.release()
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val JOB_ID = 1001
        const val RECORD_INTERVAL = 5 // minutes
    }

    private fun performPeriodicCheck() {
        Log.d(TAG, "MrXXX:Performing periodic check at ${Utils.getCurrentDateTime()}")

        if (shouldRunCheck()) {
            triggerUpdate()

            // Ensure we have fresh location and WiFi data
            if (hasLocationPermissions()) {
                CoroutineScope(Dispatchers.IO).launch {
                    fetchFreshLocation()
                }
            }else{
                Log.d(TAG, "MrXXX:performPeriodCheck permission not granted : ${Utils.getCurrentDateTime()}")

            }
            startWifiScanning()
        } else {
            Log.d(TAG, "MrXXX:Skipping periodic check - not within required conditions")
        }
    }

    private suspend fun fetchFreshLocation() = withContext(Dispatchers.Main) {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationReceived = false
        Log.d(TAG, "MrXXX:fetchFreshLocation  : ${Utils.getCurrentDateTime()}")

        try {
            // Use suspendCancellableCoroutine for location callback
            val location = suspendCancellableCoroutine<Location?> { continuation ->
                val locationListener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (!locationReceived) {
                            locationReceived = true
                            locationManager.removeUpdates(this)
                            continuation.resume(location)
                        }
                    }

                    override fun onStatusChanged(provider: String, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {
                        continuation.resume(null)
                    }
                }

                // Register for updates
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    0L,
                    0f,
                    locationListener,
                    Looper.getMainLooper()
                )

                // Set timeout
                continuation.invokeOnCancellation {
                    locationManager.removeUpdates(locationListener)
                }

                // Fallback to last known location after timeout
                CoroutineScope(Dispatchers.IO).launch {
                    delay(10000) // 10 second timeout
                    if (!locationReceived) {
                        val lastLocation = getLastKnownLocation(locationManager)
                        continuation.resume(lastLocation)
                    }
                }
            }

            location?.let {
                Log.i(TAG, "MrXXX: Fresh location obtained: ${it.latitude}, ${it.longitude}")
                makeApiCallOnce(it.latitude, it.longitude)
            } ?: run {
                Log.i(TAG, "MrXXX: No fresh location available, using last known or default")
                makeApiCallOnce(0.0, 0.0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "MrXXX: Location fetch error", e)
            makeApiCallOnce(0.0, 0.0)
        }
    }

    private fun getLastKnownLocation(locationManager: LocationManager): Location? {
        return if (hasLocationPermissions()) {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } else {
            null
        }
    }
    private fun hasLocationPermissions(): Boolean {
        return ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun triggerUpdate() {
        Log.i(TAG, "triggerUpdate: ")
        val intent = Intent("UPDATE_NOTIFICATION").apply {
            putExtra("message", "Update Data")
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun shouldRunCheck(): Boolean {
        val user = SharedPref.getInstance(this)?.getUser() ?: return false
        val today = getCurrentDayName()
        val currentTime = Calendar.getInstance()

        return user.timetable?.range?.any { shift ->
            shift.day.equals(today, true) &&
                    shift.start != null &&
                    shift.end != null &&
                    isTimeBetween(currentTime, shift.start, shift.end)
        } ?: false
    }


    private fun updateNotification(message: String) {
        isTimeReceiverDataComes = message == "Update Data"
        try {
            notificationManager?.notify(NOTIFICATION_ID, createNotification(message))
            if (isTimeReceiverDataComes) {
                CoroutineScope(Dispatchers.IO).launch {
                    callApiData()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update notification", e)
        }
    }


    private suspend fun callApiData() {
//        Log.i(TAG, "callApiData: at ${Utils.getCurrentDateTime()}")
        try {
            if (ActivityCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {

                CoroutineScope(Dispatchers.Main).launch {

                    val locationManager =
                        getSystemService(Context.LOCATION_SERVICE) as LocationManager
                    locationReceived = false // Reset before starting location request

                    val locationListener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            if (!locationReceived) {
                                locationReceived = true
                                locationManager.removeUpdates(this) // Stop further updates

                                Log.i(
                                    TAG,
                                    "callApiData: Fresh location received: ${location.latitude}, ${location.longitude} at ${Utils.getCurrentDateTime()}"
                                )
                                makeApiCallOnce(location.latitude, location.longitude)

                            }
                        }

                        override fun onStatusChanged(
                            provider: String?,
                            status: Int,
                            extras: Bundle?
                        ) {
                        }

                        override fun onProviderEnabled(provider: String) {}
                        override fun onProviderDisabled(provider: String) {}
                    }


                    locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        0L, 0f, locationListener
                    )

                    // Wait for location update, if not received in 10 seconds, use last known location
                    CoroutineScope(Dispatchers.IO).launch {
                        delay(10000) // Wait for 10 seconds
                        if (!locationReceived) {
                            val lastKnownLocation =
                                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                                    ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

                            if (lastKnownLocation != null) {
                                Log.i(
                                    TAG,
                                    "callApiData: Using last known location: ${lastKnownLocation.latitude}, ${lastKnownLocation.longitude}  at ${Utils.getCurrentDateTime()}"
                                )
                                makeApiCallOnce(
                                    lastKnownLocation.latitude,
                                    lastKnownLocation.longitude
                                )
                            } else {
                                Log.i(
                                    TAG,
                                    "callApiData:No location available. Using default values.  at ${Utils.getCurrentDateTime()}"
                                )
                                makeApiCallOnce(0.0, 0.0) // Default if no location is available
                            }
                        }
                    }
                }
            } else {
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
        user?.let { it1 ->
            val record = RecordModel(
                uuid = Utils.generateRandomFourDigitUuid(),
                user_id = user?._id.toString(),
                lat = lat,
                lng = lon,
                localTime = Utils.getCurrent24HourTime(),
                time = Utils.getCurrentUtcTime(),
                attendanceType = StatusEnum.default.name,
                attendanceStatus = Utils.checkInternetAndSetStatus(applicationContext),
                isForceAttendance = false,
                isLocation = track.checkLocationPermissions(),
                wifiService = wifiManager.isWifiEnabled(),
                dataService = Utils.isMobileDataEnabled(applicationContext),
                notification = Utils.isNotificationPermissionGranted(applicationContext),
                batterySaver = !Utils.isBatterySaverOn(applicationContext),
                batteryOptimization = !Utils.isBatteryOptimizationOff(applicationContext),
                wifi_list = wifiScanResults
            )
            CoroutineScope(Dispatchers.IO).launch {
                it1.timetable?.range?.let { saveDataLocally(record, it) }
                callApi(lat, lon, record, user)

                delay(20000) // Reset flag after 20 seconds
                apiCallInProgress = false
            }
        }

    }


    fun saveDataLocally(record: RecordModel, shifts: List<TimeRange>) {
        Log.i(TAG, "saveDataLocally:scheduleAlarms: ${Utils.getCurrentDateTime()}")

        val today = getCurrentDayName() // Get today's name, e.g., "Tuesday"
        Log.i(TAG, "saveDataLocally:Today's Day: $today")

        val todayShift = shifts.find { it.day.equals(today, ignoreCase = true) }

        if (todayShift != null && todayShift.start != null && todayShift.end != null) {
            Log.i(
                TAG,
                "saveDataLocally:Today's Shift -> day:${todayShift.day}, start:${todayShift.start}, end:${todayShift.end}"
            )

            val startCalendar = getCalendarForShift(todayShift.day, todayShift.start, -1)
            val endCalendar = getCalendarForShift(todayShift.day, todayShift.end, 1)

            Log.i(
                TAG,
                "saveDataLocally:startCalendar: ${startCalendar?.time} --> endCalendar: ${endCalendar?.time}"
            )

            val currentTime = Calendar.getInstance()

            if (startCalendar != null && endCalendar != null) {

                // ✅ Schedule API Worker ONLY IF current time is between shift start & end
                if (currentTime.after(startCalendar) && currentTime.before(endCalendar)) {
                    Log.i(
                        TAG,
                        "saveDataLocally:Current time is within shift period, scheduling API Worker."
                    )
                    dao.insertRecord(record)
                    sendNotificationUpdate("Data stored at ${Utils.getCurrentDateTime()}")
                } else {
                    finishAllData()
                    Log.i(
                        TAG,
                        "saveDataLocally: Current time is outside shift period, NOT scheduling API Worker."
                    )
                }
            }
        } else {
            Log.i(TAG, "saveDataLocally:No shift found for today.")
        }
    }
    @SuppressLint("MissingPermission")
    private suspend fun callApi(lat: Double, lan: Double, record: RecordModel, user: UserModel?) {
        Log.i(TAG, "MRcallApi: at ${Utils.getCurrentDateTime()} with location:${lat},${lan}")

        try {
            // Start Wi-Fi scan service
            Log.i(TAG, "MRcallApi: model:${record}")
//             Handle the data and API call based on internet availability
            if (Utils.isInternetAvailable(applicationContext)) {
                val records = dao.getAllRecords(user?._id.toString()).map { it.toDataRequest() }
                    .toMutableList()
//                records.add(record.toDataRequest())
                Log.i(TAG, "MRcallApi: networkAvailable recordModel:${records}")

                val token = SharedPref.getInstance(applicationContext)?.getToken() ?: ""
                CoroutineScope(Dispatchers.IO).launch {
                    val response = callServerApi(records, token)
                    if (response.isSuccessful) {
                        val attendanceResponse = response.body() as AttendaceResponseModel

                        if (attendanceResponse.data.isNotEmpty()) {
                            Log.i(
                                TAG,
                                "MRcallApi:attendaceResponse:${attendanceResponse.toString()}"
                            )


                            if (attendanceResponse.data[0].status.contains("You do not have access to this store today")) {
                                sendNotificationUpdate("You do not have access to this store today")
                                return@launch
                            }
                            handleSuccessfulResponse(record, response.body())

                            Log.i(TAG, "MRcallApi: record successfully sent to admin panal")
                            sendNotificationUpdate("Data synced to admin panel at ${Utils.getCurrentDateTime()}")
                        }

                        Log.i(TAG, "MRcallApi: attendacneResponse:${attendanceResponse}")

                    } else {
                        Log.i(TAG, "MRcallApi: api calls failed error:${response}")
                        handleUnsuccessfulResponse(record, response)
                    }
                }
            } else {
                Log.i(TAG, "MRcallApi: network not available recordModel:${record}")
            }
        } catch (e: Exception) {
            Log.i(TAG, "MRcallApi: exception:${e.printStackTrace()}")
        }

    }
    private fun sendNotificationUpdate(message: String) {
        Log.i(TAG, "sendNotificationUpdate: on${Utils.getCurrentDateTime()}")
        val intent = Intent("UPDATE_NOTIFICATION")
        intent.putExtra("message", message)
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
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

    private suspend fun callServerApi(data: List<DataRequest>, token: String) =
        repository.sendData(data, token)

    private suspend fun handleSuccessfulResponse(
        record: RecordModel,
        response: AttendaceResponseModel?
    ) {
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
                if (error.detail == "LOGOUT" || error.code in listOf(401, 422, 500)) {
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


    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                getString(R.string.breakfast_notification_channel_id),
                getString(R.string.breakfast_notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Service notifications"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(null, null)
                enableVibration(false)
            }

            notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager?.createNotificationChannel(channel)
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerWifiScanReceiver() {
        if (!isWifiReceiverRegistered) {
            try {
                registerReceiver(
                    wifiScanReceiver,
                    IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
                )
                isWifiReceiverRegistered = true
                Log.d(TAG, "WiFi scan receiver registered")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register WiFi receiver", e)
            }
        }
    }

    private fun registerNotificationReceiver() {
        if (!isNotificationReceiverRegistered) {
            try {
                LocalBroadcastManager.getInstance(this)
                    .registerReceiver(
                        notificationReceiver,
                        IntentFilter("UPDATE_NOTIFICATION")
                    )
                isNotificationReceiverRegistered = true
                Log.d(TAG, "Notification receiver registered")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register notification receiver", e)
            }
        }
    }

    private fun handleSamsungOptimization() {
        try {
            // Try multiple Samsung optimization paths
            val intents = listOf(
                Intent().apply {
                    component = ComponentName(
                        "com.samsung.android.sm",
                        "com.samsung.android.sm.ui.battery.BatteryActivity"
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                Intent().apply {
                    component = ComponentName(
                        "com.samsung.android.lool",
                        "com.samsung.android.sm.ui.battery.BatteryActivity"
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )

            var success = false
            for (intent in intents) {
                try {
                    startActivity(intent)
                    success = true
                    break
                } catch (e: Exception) {
                    continue
                }
            }

            if (!success) {
                requestIgnoreBatteryOptimizations()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Samsung optimization failed", e)
            requestIgnoreBatteryOptimizations()
        }
    }

    private fun handleOppoOptimization() {
        try {
            // Try multiple Oppo optimization paths
            val intents = listOf(
                Intent().apply {
                    component = ComponentName(
                        "com.coloros.oppoguardelf",
                        "com.coloros.powermanager.fuelgaue.PowerConsumptionActivity"
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                Intent().apply {
                    component = ComponentName(
                        "com.coloros.oppoguardelf",
                        "com.coloros.powermanager.PowerConsumptionActivity"
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )

            var success = false
            for (intent in intents) {
                try {
                    startActivity(intent)
                    success = true
                    break
                } catch (e: Exception) {
                    continue
                }
            }

            if (!success) {
                requestIgnoreBatteryOptimizations()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Oppo optimization failed", e)
            requestIgnoreBatteryOptimizations()
        }
    }

    private fun handleVivoOptimization() {
        try {
            // Try multiple Vivo optimization paths
            val intents = listOf(
                Intent().apply {
                    component = ComponentName(
                        "com.vivo.abe",
                        "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                Intent().apply {
                    component = ComponentName(
                        "com.vivo.abe",
                        "com.vivo.energy.EnergyManagerActivity"
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )

            var success = false
            for (intent in intents) {
                try {
                    startActivity(intent)
                    success = true
                    break
                } catch (e: Exception) {
                    continue
                }
            }

            if (!success) {
                requestIgnoreBatteryOptimizations()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vivo optimization failed", e)
            requestIgnoreBatteryOptimizations()
        }
    }

    private fun finishAllData() {
        Log.d(TAG, "Finishing all service operations")

        try {
            // Stop periodic checks
            handler.removeCallbacks(periodicCheckRunnable)

            // Release wake lock
            if (::wakeLock.isInitialized && wakeLock.isHeld) {
                wakeLock.release()
            }

            // Unregister receivers
            unregisterReceivers()

            // Stop foreground service
            stopForeground(true)
            stopSelf()

            Log.d(TAG, "Service stopped successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error while finishing service", e)
        } finally {
            isServiceRunning = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun startWifiScanning() {
        try {
            if (wifiManager.isWifiEnabled) {
                wifiManager.startScan()
                Log.d(TAG, "WiFi scan started")
            } else {
                Log.d(TAG, "WiFi is disabled, cannot scan")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start WiFi scan", e)
        }
    }

    private fun checkWifiPermissions(): Boolean {
        return ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_WIFI_STATE
        ) == PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.CHANGE_WIFI_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkShiftAndScheduleTasks() {
        val user = SharedPref.getInstance(this)?.getUser() ?: run {
            Log.w(TAG, "No user found in SharedPref")
            finishAllData()
            return
        }

        user.timetable?.range?.let { shifts ->
            val today = getCurrentDayName()
            val currentTime = Calendar.getInstance()

            shifts.find { it.day.equals(today, true) }?.let { todayShift ->
                if (todayShift.start != null && todayShift.end != null) {
                    if (isTimeBetween(currentTime, todayShift.start, todayShift.end)) {
                        Log.i(TAG, "Within shift time (${todayShift.start} - ${todayShift.end}), continuing service")
                    } else {
                        Log.i(TAG, "Outside shift time, stopping service")
                        finishAllData()
                    }
                }
            } ?: run {
                Log.i(TAG, "No shift found for today ($today), stopping service")
                finishAllData()
            }
        } ?: run {
            Log.w(TAG, "No timetable range found, stopping service")
            finishAllData()
        }
    }

    private fun unregisterReceivers() {
        // Unregister WiFi receiver
        if (isWifiReceiverRegistered) {
            try {
                unregisterReceiver(wifiScanReceiver)
                isWifiReceiverRegistered = false
                Log.d(TAG, "WiFi scan receiver unregistered")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "WiFi receiver already unregistered")
            }
        }

        // Unregister notification receiver
        if (isNotificationReceiverRegistered) {
            try {
                LocalBroadcastManager.getInstance(this).unregisterReceiver(notificationReceiver)
                isNotificationReceiverRegistered = false
                Log.d(TAG, "Notification receiver unregistered")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Notification receiver already unregistered")
            }
        }
    }

    private fun createNotification(message: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(
            this,
            getString(R.string.breakfast_notification_channel_id)
        )
            .setContentTitle("ShiftSmart Plus Service")
            .setContentText(message)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
            .build()
    }

    private fun getCurrentDayName(): String {
        return SimpleDateFormat("EEEE", Locale.getDefault()).format(Date())
    }

    private fun isTimeBetween(currentTime: Calendar, startTimeStr: String, endTimeStr: String): Boolean {
        return try {
            val dateFormat = SimpleDateFormat("hh:mm", Locale.getDefault())
            val startTime = dateFormat.parse(startTimeStr)
            val endTime = dateFormat.parse(endTimeStr)

            val startCal = Calendar.getInstance().apply {
                time = startTime
                set(Calendar.YEAR, currentTime.get(Calendar.YEAR))
                set(Calendar.MONTH, currentTime.get(Calendar.MONTH))
                set(Calendar.DAY_OF_MONTH, currentTime.get(Calendar.DAY_OF_MONTH))
            }

            val endCal = Calendar.getInstance().apply {
                time = endTime
                set(Calendar.YEAR, currentTime.get(Calendar.YEAR))
                set(Calendar.MONTH, currentTime.get(Calendar.MONTH))
                set(Calendar.DAY_OF_MONTH, currentTime.get(Calendar.DAY_OF_MONTH))
            }

            if (endCal.before(startCal)) {
                endCal.add(Calendar.DAY_OF_MONTH, 1)
            }

            !currentTime.before(startCal) && !currentTime.after(endCal)
        } catch (e: ParseException) {
            Log.e(TAG, "Error parsing shift times", e)
            false
        }
    }
}

// Required companion classes
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == "PERIODIC_CHECK") {
            val serviceIntent = Intent(context, MyService::class.java).apply {
                action = "RESTART_SERVICE"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
class MyJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        val serviceIntent = Intent(this, MyService::class.java).apply {
            action = "RESTART_SERVICE"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)

            // Create temporary notification to satisfy Android requirements
            val notification = createTempNotification()
            startForeground(JOB_SERVICE_NOTIFICATION_ID, notification)

            // This service will stop itself after starting the main service
            Handler(Looper.getMainLooper()).postDelayed({
                stopSelf()
            }, 1000)
        } else {
            startService(serviceIntent)
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        return true // Reschedule if job fails
    }

    private fun createTempNotification(): Notification {
        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "job_service_channel",
                "Job Service",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Temporary notification for job service"
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
            "job_service_channel"
        } else {
            ""
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("ShiftSmart Plus")
            .setContentText("Preparing service...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val JOB_SERVICE_NOTIFICATION_ID = 1002
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED ||
            intent?.action == "android.intent.action.QUICKBOOT_POWERON" ||
            intent?.action == "com.htc.intent.action.QUICKBOOT_POWERON") {

            val serviceIntent = Intent(context, MyService::class.java).apply {
                action = "START_SERVICE"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}

class NetworkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (isInternetAvailable(context)) {
            val serviceIntent = Intent(context, MyService::class.java).apply {
                action = "RESTART_SERVICE"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }

    private fun isInternetAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        return capabilities != null &&
                (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
    }
}

