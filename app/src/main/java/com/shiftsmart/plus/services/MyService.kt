package com.shiftsmart.plus.services

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
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST

import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.shiftsmart.plus.R
import com.shiftsmart.plus.database.DBDao
import com.shiftsmart.plus.database.RecordModel
import com.shiftsmart.plus.database.ShiftSmartPlusDatabase
import com.shiftsmart.plus.enums.StatusEnum
import com.shiftsmart.plus.models.WifiModel
import com.shiftsmart.plus.periodicAction.AlarmReceiver
import com.shiftsmart.plus.repository.MainRepository
import com.shiftsmart.plus.ui.activities.MainActivity
import com.shiftsmart.plus.utils.AttendanceSyncManager
import com.shiftsmart.plus.utils.BatteryOptimizationHelper
import com.shiftsmart.plus.utils.LocationHelper
import com.shiftsmart.plus.utils.SharedPref
import com.shiftsmart.plus.utils.ShiftUtils
import com.shiftsmart.plus.utils.Utils
import com.shiftsmart.plus.utils.Utils.getCurrentDayName
import com.shiftsmart.plus.utils.Utils.toLocalDate
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.LocalDate

import java.util.Calendar
import java.util.Date
import java.util.Locale

import javax.inject.Inject
import kotlin.collections.addAll
import kotlin.text.clear

@AndroidEntryPoint
class MyService : Service() {

    @Inject
    lateinit var repository: MainRepository
    @Inject
    lateinit var track: LocationTrack
    @Inject
    lateinit var db: ShiftSmartPlusDatabase

    @Inject
    lateinit var context: Context

    private lateinit var dao: DBDao
    private var notificationManager: NotificationManager? = null
    private val TAG = "MyService"

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var wifiManager: WifiManager
    private var isWifiReceiverRegistered = false


    private fun updateForegroundNotification(context: Context, message: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = createNotification(message)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    // Wake lock
    private lateinit var wakeLock: PowerManager.WakeLock
    private var isServiceRunning = false

    private val wifiScanResults = mutableListOf<WifiModel>()

    // Broadcast Receivers
    private val wifiScanReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false) == true) {
                wifiScanResults.clear()
                wifiScanResults.addAll(wifiManager.scanResults.map { WifiModel(it.SSID, it.BSSID, it.level) })

                setWifiList(wifiScanResults)
            }
        }
    }
    private var isNotificatoinRegistered = false
    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra("message")
            Log.i("NotificationReceiver", "Received notification update: $message")
            if (context != null) {
                updateForegroundNotification(context,message.toString())
            }
        }
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    @Inject lateinit var locationHelper: LocationHelper

    @Inject lateinit var attendanceSyncManager: AttendanceSyncManager


    fun setWifiList(wifiList: MutableList<WifiModel>) {
        wifiScanResults.clear()
        wifiScanResults.addAll(wifiList);
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
        isServiceRunning = true

        acquireWakeLock()
        initializeComponents()
        registerReceivers()
        checkBatteryOptimizations()


    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Service command received: ${intent?.action}")

        // Determine appropriate message based on shift status
        val user = SharedPref.getInstance(this)?.getUser()
        val isInShift = if (user != null) {
            attendanceSyncManager.shouldRunCheck(user)
        } else {
            false
        }

        val initialMessage = when {
            !isInShift -> "Off-shift - Service idle"
            intent?.action == ACTION_CALL_API -> "Syncing attendance data..."
            intent?.action == ACTION_START -> "Starting attendance tracking..."
            intent?.action == ACTION_STOP -> "Stopping service..."
            else -> "Attendance tracking active"
        }

        // Always start foreground early to avoid crash
        val notification = createNotification(initialMessage)
        startForeground(NOTIFICATION_ID, notification)

        when (intent?.action) {
            ACTION_START -> handleServiceStart()
            ACTION_STOP -> handleServiceStop()
            ACTION_RESTART -> handleServiceRestart()
            ACTION_CALL_API -> {
                // Extract parameters passed from AlarmReceiver
                val lastRecordTimeStr = intent.getStringExtra("LAST_RECORD_TIME")
                val targetTimeStr = intent.getStringExtra("TARGET_TIME")
                val missingRecords = intent.getIntExtra("MISSING_RECORDS", 0)

                checkAndMaintainService(lastRecordTimeStr, targetTimeStr, missingRecords)
            }

            else -> handleRegularStart()
        }

        return START_STICKY
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
            acquire(10 * 60 * 1000L)// 10 minutes

        }
    }

    private fun checkBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                BatteryOptimizationHelper.checkBatteryOptimizations(this)
            }
        }
    }


    // Public method to trigger finishAllData from other components
    fun finishServiceOperations() {
        finishAllData()
    }

    fun finishAllData() {
        Log.d(TAG, "Finishing all service operations")

        try {

            // Release wake lock
            if (::wakeLock.isInitialized && wakeLock.isHeld) {
                wakeLock.release()
            }
            serviceJob.cancel()
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

        if (isNotificatoinRegistered) {
            try {
                unregisterReceiver(notificationReceiver)
                isNotificatoinRegistered = false
                Log.d(TAG, "notification receiver unregistered")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "notification already unregistered")
            }
        }

    }

    private fun initializeComponents() {
        dao = db.dbDao()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        createNotificationChannel()
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
    private fun registerReceivers() {
        registerWifiScanReceiver()
        registerNotificationReceiver()

    }
    private fun registerNotificationReceiver() {
        if (!isNotificatoinRegistered) {
            try {
                LocalBroadcastManager.getInstance(this).registerReceiver(notificationReceiver, IntentFilter("UPDATE_NOTIFICATION"))
                isNotificatoinRegistered = true
                Log.d(TAG, "Notification receiver registered")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register notification receiver", e)
            }
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

    private var lastCheckDate: LocalDate? = null

    /**
     * checkAndMaintainService handles both regular checks and missing record insertion.
     * When called from AlarmReceiver with parameters, it inserts missing records at exact times.
     */
    private fun checkAndMaintainService(
        lastRecordTimeStr: String? = null,
        targetTimeStr: String? = null,
        missingRecords: Int = 0
    ) {
        Log.d(TAG, "🔄 checkAndMaintainService: ${Utils.getCurrentDateTime()}")
        Log.d(TAG, "📊 Parameters: lastRecord=$lastRecordTimeStr, target=$targetTimeStr, missing=$missingRecords")

        val today = LocalDate.now()

        // Force re-sync at day change
        if (lastCheckDate != null && today.isAfter(lastCheckDate)) {
            Log.i(TAG, "🕛 Day changed → forcing midnight sync.")
            updateForegroundNotification(this, "Day changed - Syncing data...")
            forceMidnightSync()
        }
        lastCheckDate = today

        // Check shift status using AttendanceSyncManager
        val user = SharedPref.getInstance(this)?.getUser()
        if (user == null) {
            Log.e(TAG, "❌ No user found - Stopping service")
            updateForegroundNotification(this, "No user - Service stopping...")
            finishServiceOperations()
            return
        }

        val isInsideShift = attendanceSyncManager.shouldRunCheck(user)
        if (!isInsideShift) {
            Log.d(TAG, "❌ Off-shift - Stopping service")
            updateForegroundNotification(this, "Off-shift - Service stopping...")
            finishServiceOperations()
            return
        }

        updateForegroundNotification(this, "📝 Tracking attendance...")

        // ✅ Handle missing records insertion if alarm detected a gap
        if (missingRecords > 0 && lastRecordTimeStr != null && targetTimeStr != null) {
            Log.i(TAG, "📝 Gap detected: Inserting $missingRecords missing record(s) from $lastRecordTimeStr to $targetTimeStr")

            serviceScope.launch {
                try {
                    // First fetch current location and wifi data
                    val location = if (locationHelper.hasLocationPermissions()) {
                        try {
                            locationHelper.fetchFreshLocation()
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to fetch location, using last known", e)
                            locationHelper.lastLocation
                        }
                    } else {
                        locationHelper.lastLocation
                    }

                    // Create a template record with current data
                    val templateRecord = RecordModel(
                        uuid = Utils.generateRandomUuid(),
                        user_id = user._id.toString(),
                        lat = location.latitude,
                        lng = location.longitude,
                        localTime = Utils.getCurrent24HourTime(),
                        time = Utils.getCurrentUtcTime(),
                        attendanceType = StatusEnum.default.name,
                        attendanceStatus = Utils.checkInternetAndSetStatus(this@MyService),
                        isForceAttendance = false,
                        isLocation = locationHelper.hasLocationPermissions(),
                        wifiService = wifiManager.isWifiEnabled,
                        dataService = Utils.isMobileDataEnabled(this@MyService),
                        notification = Utils.isNotificationPermissionGranted(this@MyService),
                        batterySaver = !Utils.isBatterySaverOn(this@MyService),
                        batteryOptimization = !Utils.isBatteryOptimizationOff(this@MyService),
                        wifi_list = wifiScanResults.toList()
                    )

                    // Insert missing records at exact intervals (fills the gap)
                    attendanceSyncManager.insertMissingRecordsAtExactIntervals(
                        lastRecordTimeStr = lastRecordTimeStr,
                        targetTimeStr = targetTimeStr,
                        numberOfRecords = missingRecords,
                        currentRecord = templateRecord,
                        user = user
                    )

                    // After filling gap, insert current record at target time
                    Log.i(TAG, "✅ Gap filled, now inserting current record at $targetTimeStr")
                    startLocationFetch()

                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error inserting missing records", e)
                }
            }
        } else {
            // No gap detected OR missingRecords = 0 - just insert current record normally
            Log.i(TAG, "✅ No gap detected - inserting current record at ${Utils.getCurrent24HourTime()}")
            serviceScope.launch {
                startLocationFetch()
            }
        }
    }



    private fun forceMidnightSync() {
        serviceScope.launch {
            attendanceSyncManager.startSyncProcess() // or your backup save method
            Log.i(TAG, "Midnight sync completed.")
        }
    }


    private fun releaseWakeLock() {
        if (::wakeLock.isInitialized && wakeLock.isHeld) {
            wakeLock.release()
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

    // Function to make API call only once

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

    private fun startLocationFetch() {
        if (!locationHelper.hasLocationPermissions()) {
            Log.w(TAG, "MrXXX: Location permission not granted — skipping location fetch")
            maybeTriggerApiCall()
            return
        }

        serviceScope.launch {
            try {
                val freshLatLng = locationHelper.fetchFreshLocation()
                Log.i(TAG, "MrXXX: Fresh location obtained: ${freshLatLng.latitude}, ${freshLatLng.longitude} at: ${Utils.getCurrentDateTime()}")
                maybeTriggerApiCall()
            } catch (e: Exception) {
                Log.e(TAG, "MrXXX: Failed to fetch location", e)
                maybeTriggerApiCall()
            }
        }
    }


    /**
     * Creates a new attendance record and passes it to AttendanceSyncManager.
     * The manager handles all validation, gap filling, duplicate checking, and syncing.
     * If off-shift is detected, the service will be stopped.
     */
    private fun maybeTriggerApiCall() {
        serviceScope.launch {
            try {
                val user = SharedPref.getInstance(this@MyService)?.getUser()
                if (user == null) {
                    Log.e(TAG, "❌ No user found, skipping record save")
                    return@launch
                }

                // Create record with current data
                val record = RecordModel(
                    uuid = Utils.generateRandomUuid(),
                    user_id = user._id.toString(),
                    lat = locationHelper.lastLocation.latitude,
                    lng = locationHelper.lastLocation.longitude,
                    localTime = Utils.getCurrent24HourTime(),
                    time = Utils.getCurrentUtcTime(),
                    attendanceType = StatusEnum.default.name,
                    attendanceStatus = Utils.checkInternetAndSetStatus(this@MyService),
                    isForceAttendance = false,
                    isLocation = locationHelper.hasLocationPermissions(),
                    wifiService = wifiManager.isWifiEnabled,
                    dataService = Utils.isMobileDataEnabled(this@MyService),
                    notification = Utils.isNotificationPermissionGranted(this@MyService),
                    batterySaver = !Utils.isBatterySaverOn(this@MyService),
                    batteryOptimization = !Utils.isBatteryOptimizationOff(this@MyService),
                    wifi_list = wifiScanResults.toList()
                )

                Log.i(TAG, "📝 Created record at ${record.localTime} (UTC: ${record.time})")

                // ✅ AttendanceSyncManager handles:
                // 1. Shift validation (checks if time is within shift window)
                // 2. Duplicate checking (prevents same UTC time records)
                // 3. Gap filling (inserts missing 5-minute interval records)
                // 4. Database insertion
                // 5. API sync (if internet available)
                val isInsideShift = attendanceSyncManager.saveRecordLocally(record, user)

                if (isInsideShift) {
                    Log.i(TAG, "✅ Record saved successfully - Service continues")
                } else {
                    Log.w(TAG, "⚠️ Off-shift detected - Stopping service")
                    updateForegroundNotification(this@MyService, "Off-shift - Service stopping...")
                    finishServiceOperations()
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error creating record", e)
            }
        }
    }


    private fun handleServiceStart() {
        Log.i(TAG, "Starting service")
        updateForegroundNotification(this, "Attendance tracking started")
        startForegroundService()
    }

    private fun handleServiceStop() {
        Log.i(TAG, "Stopping service")
        updateForegroundNotification(this, "Service stopped")
        finishServiceOperations()
    }
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun handleServiceRestart() {

        Log.i(TAG, "Restarting service")
        if (!isServiceRunning) {
            startForegroundService()
            startWifiScanning()
            checkShiftAndScheduleTasks()
        }
    }

    private fun checkShiftAndScheduleTasks() {
        val user = SharedPref.getInstance(this)?.getUser() ?: run {
            Log.w(TAG, "No user found in SharedPref")
            updateForegroundNotification(this, "No user - Service stopping")
            finishServiceOperations()
            return
        }

        // Use AttendanceSyncManager to check shift status with detailed logging
        val isInsideShift = attendanceSyncManager.shouldRunCheck(user)

        if (isInsideShift) {
            Log.i(TAG, "✅ Within shift time, continuing service")
            updateForegroundNotification(this, "In-shift - Tracking active")
            // keep service alive, do nothing
        } else {
            Log.i(TAG, "❌ Outside shift window, stopping service")
            updateForegroundNotification(this, "Off-shift - Service stopping")
            finishServiceOperations()
        }
    }


    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startForegroundService() {
        val notification = createNotification("Service started").apply {
            flags = flags or Notification.FLAG_NO_CLEAR
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun handleRegularStart() {


        startForegroundService()
        startWifiScanning()
        checkShiftAndScheduleTasks()
    }

    @RequiresApi(Build.VERSION_CODES.Q)

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false

        try {
            releaseWakeLock()
            unregisterReceivers()

        } catch (e: Exception) {
            Log.e(TAG, "Error during onDestroy", e)
        }
    }

    override fun onBind(p0: Intent?): IBinder? {
        return null
    }



    companion object {
        private const val NOTIFICATION_ID = 1
        const val ACTION_START = "START_SERVICE"
        const val ACTION_STOP = "STOP_SERVICE"
        const val ACTION_CALL_API = "CALL_API"
        const val ACTION_RESTART = "RESTART_SERVICE"
        const val ACTION_SYNC_DATA = "SYNC_DATA" // 👈 add this new one
    }

}

class ServiceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == "com.shiftsmart.plus.ACTION_FINISH") {
            (context as? MyService)?.finishAllData()
        }
    }
}




