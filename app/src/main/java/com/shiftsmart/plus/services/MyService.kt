package com.shiftsmart.plus.services

import android.Manifest
import android.annotation.SuppressLint
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
import com.shiftsmart.plus.models.UserModel
import com.shiftsmart.plus.models.WifiModel
import com.shiftsmart.plus.periodicAction.AlarmScheduler
import com.shiftsmart.plus.periodicAction.WifiScanWorker
import com.shiftsmart.plus.repository.MainRepository
import com.shiftsmart.plus.ui.activities.MainActivity
import com.shiftsmart.plus.utils.SharedPref
import com.shiftsmart.plus.utils.Utils
import com.shiftsmart.plus.utils.parseErrorBody
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Response
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

         dao=db.dbDao()

        addWifiScanner()

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        CoroutineScope(Dispatchers.Main).launch {
            delay(2000) // Delay for 2 seconds (2000 milliseconds)
            createNotificationChannel()
            notificationManager = getSystemService(NotificationManager::class.java)
            registerWifiScanReceiver()
            val filter = IntentFilter("UPDATE_NOTIFICATION")
            LocalBroadcastManager.getInstance(applicationContext).registerReceiver(notificationReceiver, filter)
            Log.i(TAG, "onCreate: service oncreate")
            startForeground(1, createNotification("Initializing Service..."))
        }

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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        addWifiScanner()
        intent?.let {
            Log.i(
                TAG,
                "onStartCommand: before intent :${it.hasExtra("action")} at ${Utils.getCurrentDateTime()}"
            )
            if (it.hasExtra("action") && it.getStringExtra("action") == "STOP_SERVICE") {
                Log.i(TAG, "onStartCommand: after action")
                finishAllData()

            }
        }
          return START_STICKY // Ensures the service restarts if killed
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
            !message.contains("Data saved in database at", ignoreCase = true)) {
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
        Log.i(TAG, "updateNotification: at ${Utils.getCurrentDateTime()}")

        if (notificationManager != null) {
            notificationManager!!.notify(1, createNotification(message))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i("MyService", "onDestroy: ")

        finishAllData()

    }

    private fun finishAllData() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(notificationReceiver)
        WorkManager.getInstance(this).cancelAllWorkByTag(WORK_TAG)
        AlarmScheduler.cancelAlarms(this)  // Stop future alarms

        // Clear all notifications posted by your app
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancelAll()
        unregisterWifiScanReceiver()  // Unregister the Wi-Fi scan receiver
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
            batterySaver = Utils.isBatterySaverOn(applicationContext),
            batteryOptimization = Utils.isBatteryOptimizationOff(applicationContext),
            wifi_list = wifiScanResults
        )
               CoroutineScope(Dispatchers.IO).launch {
                   dao.insertRecord(record)
                  callApi(lat, lon,record,user)
                  delay(20000) // Reset flag after 20 seconds
                 apiCallInProgress = false
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
                records.add(record.toDataRequest())
                Log.i(TAG, "MRcallApi: networkAvailable recordModel:${records}")

                val token = SharedPref.getInstance(applicationContext)?.getToken() ?: ""
                CoroutineScope(Dispatchers.IO).launch {
                    val response = callServerApi(records, token)
                    if (response.isSuccessful) {
                        Log.i(TAG, "MRcallApi: record successfully sent to admin panal")
                        handleSuccessfulResponse(record, response.body())
                        sendNotificationUpdate("Data synced to admin panel at ${Utils.getCurrentDateTime()}")
                    } else {
                        Log.i(TAG, "MRcallApi: api calls failed error:${response}")

                        handleUnsuccessfulResponse(record,response)
                    }
                }
            }
            else {
                Log.i(TAG, "MRcallApi: network not available recordModel:${record}")

                CoroutineScope(Dispatchers.IO).launch {
                    dao.insertRecord(record)
                    sendNotificationUpdate("Data saved in database at ${Utils.getCurrentDateTime()}")
                }
            }
        }
        catch (e:Exception)
        {
            CoroutineScope(Dispatchers.IO).launch {
                dao.insertRecord(record)
                sendNotificationUpdate("Data saved in database at ${Utils.getCurrentDateTime()}")
            }
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
            errorResponse?.errors?.firstOrNull()?.let { error ->
                if (error.detail == "LOGOUT" || error.code in listOf(401, 422)) {
                    withContext(Dispatchers.IO) {
                        dao.deleteAllRecords()
                        SharedPref.getInstance(applicationContext)?.clearPrefrence()
                    }
                }else{
                    CoroutineScope(Dispatchers.IO).launch {
                        dao.insertRecord(record)
                        sendNotificationUpdate("Data saved in database at ${Utils.getCurrentDateTime()}")
                    }
                }

            }
        }
    }
    private fun sendNotificationUpdate(message: String) {
        Log.i(TAG, "sendNotificationUpdate: on${Utils.getCurrentDateTime()}")
        val intent = Intent("UPDATE_NOTIFICATION")
        intent.putExtra("message", message)


        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
    }


}
