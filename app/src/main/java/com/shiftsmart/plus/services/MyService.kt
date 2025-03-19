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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
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
import com.shiftsmart.plus.periodicAction.AlarmScheduler.scheduleApiWorker
import com.shiftsmart.plus.periodicAction.TimeChangeReceiver
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
                user_id = user?.id.toString(),
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
             /*   if (isTimeReciverDataComes)
                {
                    callApi(lat, lon, record, user)
                }*/

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
                val records = dao.getAllRecords(user?.id.toString()).map { it.toDataRequest() }
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

}
