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
import com.shiftsmart.plus.models.UserModel
import com.shiftsmart.plus.models.WifiModel
import com.shiftsmart.plus.periodicAction.AlarmReceiver
import com.shiftsmart.plus.periodicAction.AlarmScheduler
import com.shiftsmart.plus.periodicAction.RestartServiceReceiver
import com.shiftsmart.plus.periodicAction.RestartWatchdogManager
import com.shiftsmart.plus.periodicAction.ServiceHealthWorkerManager
import com.shiftsmart.plus.repository.MainRepository
import com.shiftsmart.plus.ui.activities.MainActivity
import com.shiftsmart.plus.utils.AttendanceSyncManager
import com.shiftsmart.plus.utils.BatteryOptimizationHelper
import com.shiftsmart.plus.utils.LocationHelper
import com.shiftsmart.plus.utils.SharedPref
import com.shiftsmart.plus.utils.Utils
import com.shiftsmart.plus.utils.WifiScanner
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.LocalDate

import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.Timer
import java.util.TimerTask

import javax.inject.Inject
import kotlin.collections.addAll
import kotlin.text.clear
import kotlin.text.format
import kotlin.text.get
import kotlin.text.set
import kotlin.times
import kotlin.toString


@AndroidEntryPoint
class MyService : Service() {


    @Inject
    lateinit var db: ShiftSmartPlusDatabase

    @Inject
    lateinit var context: Context

    private lateinit var dao: DBDao
    private var notificationManager: NotificationManager? = null
    private val TAG = "MyService"

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var wifiScanner: WifiScanner
    private var currentIntent: Intent? = null  // ✅ Store intent for access in maybeTriggerApiCall


    private fun updateForegroundNotification(context: Context, message: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = createNotification(message)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    // Wake lock
    private lateinit var wakeLock: PowerManager.WakeLock
    private var isServiceRunning = false

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    @Inject lateinit var locationHelper: LocationHelper
    @Inject lateinit var attendanceSyncManager: AttendanceSyncManager

    // Notification Broadcast Receiver
    private var isNotificationRegistered = false
    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra("message")
            Log.i("NotificationReceiver", "Received notification update: $message")
            if (context != null) {
                updateForegroundNotification(context, message.toString())
            }
        }
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int
    {
        currentIntent = intent
        Log.i(TAG, "Service command received: ${intent?.action}")

        val user = SharedPref.getInstance(this)?.getUser()
        val isInShift = if (user != null) attendanceSyncManager.shouldRunCheck(user) else false

        // ✅ Only update notification when START/STOP/RESTART — NOT on CALL_API or COLLECT_AND_STOP
        if (intent?.action != ACTION_CALL_API && intent?.action != ACTION_COLLECT_AND_STOP) {
            val initialMessage = when {
                !isInShift -> "Off-shift - Service idle"
                intent?.action == ACTION_START -> "Starting attendance tracking..."
                intent?.action == ACTION_STOP -> "Stopping service..."
                else -> "Attendance tracking active"
            }
            val notification = createNotification(initialMessage)
            startForeground(NOTIFICATION_ID, notification)
        } else if (intent?.action == ACTION_COLLECT_AND_STOP) {
            // ✅ For COLLECT_AND_STOP, start foreground with brief message
            val notification = createNotification("Collecting data...")
            startForeground(NOTIFICATION_ID, notification)
        }

        when (intent?.action) {
            ACTION_START -> handleServiceStart()
            ACTION_STOP -> handleServiceStop()
            ACTION_RESTART -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    handleServiceRestart()
                } else {
                    handleServiceStart()
                }
            }
            ACTION_CALL_API -> {
                // ✅ Only trigger work, DO NOT touch notification
                Log.i(TAG, "🔄 CALL_API invoked — service already running, no notification update")
                checkAndMaintainService() // or directly maybeTriggerApiCall()
            }
            ACTION_COLLECT_AND_STOP -> {
                // ✅ NEW: Wake up, collect data, save, sync, then stop
                Log.i(TAG, "🔄 COLLECT_AND_STOP invoked — will collect data and stop after completion")
                handleCollectAndStop()
            }
            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    handleRegularStart()
                } else {
                    handleServiceStart()
                }
            }
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

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun handleServiceRestart() {
        Log.i(TAG, "Restarting service")
        // ✅ Always restart when ACTION_RESTART is received (emergency restart from onDestroy/onTaskRemoved)
        startServiceInForeground()
        checkShiftAndScheduleTasks()
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

            // ✅ Stop foreground but KEEP notification visible and dismissable
            // STOP_FOREGROUND_DETACH = notification stays, user can dismiss it
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_DETACH)
            } else {
                stopForeground(false) // Keep notification on older devices
            }

            stopSelf()

            Log.d(TAG, "Service stopped successfully - notification remains dismissable")
        } catch (e: Exception) {
            Log.e(TAG, "Error while finishing service", e)
        } finally {
            isServiceRunning = false
        }

    }

    private fun unregisterReceivers() {
        // Unregister notification receiver
        if (isNotificationRegistered) {
            try {
                LocalBroadcastManager.getInstance(this).unregisterReceiver(notificationReceiver)
                isNotificationRegistered = false
                Log.d(TAG, "Notification receiver unregistered")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Notification receiver already unregistered")
            }
        }

    }

    private fun initializeComponents() {
        dao = db.dbDao()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        wifiScanner = WifiScanner(this)
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
        registerNotificationReceiver()
    }

    private fun registerNotificationReceiver() {
        if (!isNotificationRegistered) {
            try {
                LocalBroadcastManager.getInstance(this).registerReceiver(notificationReceiver, IntentFilter("UPDATE_NOTIFICATION"))
                isNotificationRegistered = true
                Log.d(TAG, "Notification receiver registered")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register notification receiver", e)
            }
        }
    }


    private var lastCheckDate: LocalDate? = null

    private fun checkAndMaintainService(
    ) {
        Log.d(TAG, "🔄 checkAndMaintainService: ${Utils.getCurrentDateTime()}")

        val today = LocalDate.now()

        if (lastCheckDate != null && today.isAfter(lastCheckDate)) {
            Log.i(TAG, "🕛 Day changed → forcing midnight sync.")
            updateForegroundNotification(this, "Day changed - Syncing data...")
            forceMidnightSync()
        }
        lastCheckDate = today

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

        // ✅ Collect data and let AttendanceSyncManager handle everything
        serviceScope.launch(Dispatchers.IO) {
            try {
                Log.i(TAG, "✅ Starting data collection")

                // Fetch location (if permission granted)
                if (locationHelper.hasLocationPermissions()) {
                    val latch = java.util.concurrent.CountDownLatch(1)
                    locationHelper.fetchFreshLocation { latLng, error ->
                        if (error == null && latLng != null) {
                            Log.i(TAG, "✅ Location: ${latLng.latitude}, ${latLng.longitude}")
                        } else {
                            Log.e(TAG, "❌ Location error: $error")
                        }
                        latch.countDown()
                    }
                    latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
                }

                // Get fresh WiFi scan results using new WifiScanner
                Log.i(TAG, "📶 Fetching fresh WiFi list...")
                val wifiList = wifiScanner.getFreshWifiList()
                Log.i(TAG, "📶 WiFi scan complete: ${wifiList.size} networks found")

                // Create record
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
                    wifiService = wifiScanner.isWifiEnabled(),
                    dataService = Utils.isMobileDataEnabled(this@MyService),
                    notification = Utils.isNotificationPermissionGranted(this@MyService),
                    batterySaver = !Utils.isBatterySaverOn(this@MyService),
                    batteryOptimization = !Utils.isBatteryOptimizationOff(this@MyService),
                    wifi_list = wifiList
                )

                Log.i(TAG, "📝 Record created at ${record.localTime}")

                // ✅ AttendanceSyncManager handles: validation, saving, API sync
                attendanceSyncManager.saveRecordLocally(record, user) { isInsideShift, syncMessage ->
                    if (isInsideShift) {
                        Log.i(TAG, "✅ Record processed: $syncMessage")
                        updateForegroundNotification(this@MyService, syncMessage)
                    } else {
                        Log.w(TAG, "⚠️ Off-shift: $syncMessage")
                        updateForegroundNotification(this@MyService, "Off-shift - Service stopping...")
                        finishServiceOperations()
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error in checkAndMaintainService", e)
                updateForegroundNotification(this@MyService, "Error tracking - retrying...")
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

    private fun createNotification(message: String, isDismissable: Boolean = false): Notification {
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
            .setOngoing(!isDismissable) // ✅ Allow dismissal after data is saved
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

    private fun handleServiceStart() {
        Log.i(TAG, "Starting service")
        updateForegroundNotification(this, "Attendance tracking started")
        startServiceInForeground()

        // ✅ Ensure backup mechanisms are active
        Log.i(TAG, "Initializing backup mechanisms...")

        // 1. Schedule 15-minute health check (WorkManager)
        ServiceHealthWorkerManager.schedulePeriodicHealthCheck(this)

        // 2. Schedule next CALL_API alarm (AlarmManager)
        AlarmReceiver.scheduleNextAlignedAlarm(this)

        RestartWatchdogManager.scheduleOneMinuteRestart(this)

        Log.i(TAG, "✅ Backup mechanisms initialized")
    }

    private fun handleServiceStop() {
        Log.i(TAG, "🛑 Stopping service - saving final record first")

        val user = SharedPref.getInstance(this)?.getUser()
        if (user == null) {
            Log.e(TAG, "❌ No user found - Stopping immediately")
            updateForegroundNotification(this, "Service stopped")
            finishServiceOperations()
            return
        }

        // ✅ Show notification that service is stopping
        updateForegroundNotification(this, "Saving final record...")

        // ✅ Collect and save final record
        serviceScope.launch(Dispatchers.IO) {
            try {
                // Fetch location if available
                if (locationHelper.hasLocationPermissions()) {
                    val latch = java.util.concurrent.CountDownLatch(1)
                    locationHelper.fetchFreshLocation { latLng, error ->
                        latch.countDown()
                    }
                    latch.await(5, java.util.concurrent.TimeUnit.SECONDS) // Shorter timeout
                }

                // Get fresh WiFi scan results using new WifiScanner
                Log.i(TAG, "📶 Fetching WiFi list for final record...")
                val wifiList = wifiScanner.getFreshWifiList()
                Log.i(TAG, "📶 WiFi scan complete: ${wifiList.size} networks found")

                // Create final record
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
                    wifiService = wifiScanner.isWifiEnabled(),
                    dataService = Utils.isMobileDataEnabled(this@MyService),
                    notification = Utils.isNotificationPermissionGranted(this@MyService),
                    batterySaver = !Utils.isBatterySaverOn(this@MyService),
                    batteryOptimization = !Utils.isBatteryOptimizationOff(this@MyService),
                    wifi_list = wifiList
                )

                Log.i(TAG, "💾 Saving final record at ${record.localTime}")

                // Save final record with callback
                attendanceSyncManager.saveRecordLocally(record, user) { isStillInShift, syncMessage ->
                    Log.i(TAG, "✅ Final record saved: $syncMessage")

                    // Show final notification (dismissable)
                    val dismissableNotification = createNotification(
                        "Service stopped - $syncMessage",
                        isDismissable = true
                    )
                    notificationManager?.notify(NOTIFICATION_ID, dismissableNotification)

                    // ✅ Auto-dismiss after 10 seconds, then stop service
                    serviceScope.launch {
                        delay(10000) // 10 seconds to read
                        Log.i(TAG, "🗑️ Auto-dismissing stop notification")
                        notificationManager?.cancel(NOTIFICATION_ID)
                        finishServiceOperations()
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error saving final record: ${e.message}", e)
                updateForegroundNotification(this@MyService, "Service stopped")
                finishServiceOperations()
            }
        }
    }

    /**
     * NEW: Handle COLLECT_AND_STOP action
     * This is triggered by AlarmManager and implements the wake-up → work → sleep pattern
     *
     * Flow:
     * 1. Validate 5-minute timing boundary
     * 2. Check shift status
     * 3. Collect location and wifi data
     * 4. Save record locally
     * 5. Sync to server
     * 6. Schedule next alarm
     * 7. Stop service
     */
    private fun handleCollectAndStop() {
        Log.i(TAG, "🔄 handleCollectAndStop: Starting data collection at ${Utils.getCurrentDateTime()}")

        val user = SharedPref.getInstance(this)?.getUser()
        if (user == null) {
            Log.e(TAG, "❌ No user found - Stopping service")
            updateForegroundNotification(this, "No user - Service stopping...")
            AlarmReceiver.scheduleNextAlarmFromCurrentTime(this)
            finishServiceOperations()
            return
        }

        // ✅ STEP 1: Validate shift status
        val isInsideShift = attendanceSyncManager.shouldRunCheck(user)
        if (!isInsideShift) {
            Log.d(TAG, "❌ Off-shift - Stopping service")
            updateForegroundNotification(this, "Off-shift - Service stopping...")
            AlarmReceiver.scheduleNextAlarmFromCurrentTime(this)
            finishServiceOperations()
            return
        }

        // ✅ STEP 2: Validate 5-minute boundary
        val currentTime = Calendar.getInstance()
        val currentMinute = currentTime.get(Calendar.MINUTE)
        val currentSecond = currentTime.get(Calendar.SECOND)

        Log.i(TAG, "📍 Current time: ${Utils.getCurrent24HourTime()} (minute: $currentMinute, second: $currentSecond)")

        if (currentMinute % 5 != 0) {
            Log.w(TAG, "⏭️ Current time NOT on 5-min boundary (${currentMinute}m) → skipping and rescheduling")

            // Calculate next 5-minute boundary
            val nextBoundaryMinute = ((currentMinute / 5) + 1) * 5
            val nextBoundaryTime = Calendar.getInstance().apply {
                if (nextBoundaryMinute >= 60) {
                    add(Calendar.HOUR_OF_DAY, 1)
                    set(Calendar.MINUTE, 0)
                } else {
                    set(Calendar.MINUTE, nextBoundaryMinute)
                }
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            val nextTimeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(nextBoundaryTime.time)
            Log.i(TAG, "⏰ Scheduling next alarm at: $nextTimeStr")

            AlarmReceiver.scheduleAtExactTime(this, nextBoundaryTime.timeInMillis)
            finishServiceOperations()
            return
        }

        Log.i(TAG, "✅ On 5-minute boundary - proceeding with data collection")

        // ✅ STEP 3: Check gap from last sync
        val sharedPref = SharedPref.getInstance(this)
        val lastSyncTimestamp = sharedPref?.getLastSyncTimestamp() ?: 0L

        if (lastSyncTimestamp > 0L) {
            val targetTime = Calendar.getInstance().apply {
                set(Calendar.MINUTE, currentMinute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val currentTimestamp = targetTime.timeInMillis
            val gapMillis = currentTimestamp - lastSyncTimestamp
            val minutesDiff = (gapMillis / (60 * 1000)).toInt()

            Log.i(TAG, "⏱️ Last sync: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(lastSyncTimestamp))}")
            Log.i(TAG, "⏱️ Current: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(currentTimestamp))}")
            Log.i(TAG, "⏱️ Gap: $minutesDiff minutes")

            when {
                minutesDiff < 5 -> {
                    Log.w(TAG, "⏸️ Gap too small ($minutesDiff min < 5) → skipping and scheduling next")
                    AlarmReceiver.scheduleNextAlignedAlarm(this)
                    finishServiceOperations()
                    return
                }
                minutesDiff % 5 != 0 -> {
                    Log.w(TAG, "⚠️ Gap not aligned ($minutesDiff min) → skipping to next aligned time")
                    val nextValidMinutes = ((minutesDiff / 5) + 1) * 5
                    val nextValidTime = lastSyncTimestamp + (nextValidMinutes * 60 * 1000)
                    val nextTimeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(nextValidTime))
                    Log.i(TAG, "⏭️ Next aligned time: $nextTimeStr")
                    AlarmReceiver.scheduleAtExactTime(this, nextValidTime)
                    finishServiceOperations()
                    return
                }
                else -> {
                    Log.i(TAG, "✅ Gap is valid multiple of 5 ($minutesDiff min) — proceeding")
                }
            }
        } else {
            Log.i(TAG, "🆕 No previous record found — starting fresh")
        }

        // ✅ STEP 4: Collect data and let AttendanceSyncManager handle everything
        updateForegroundNotification(this, "📝 Collecting data...")

        serviceScope.launch(Dispatchers.IO) {
            try {
                Log.i(TAG, "✅ Starting data collection on IO dispatcher")

                // Fetch location (if permission granted)
                if (locationHelper.hasLocationPermissions()) {
                    val latch = java.util.concurrent.CountDownLatch(1)
                    locationHelper.fetchFreshLocation { latLng, error ->
                        if (error == null && latLng != null) {
                            Log.i(TAG, "✅ Fresh location obtained: ${latLng.latitude}, ${latLng.longitude}")
                        } else {
                            Log.e(TAG, "❌ Failed to fetch location: $error")
                        }
                        latch.countDown()
                    }
                    latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
                } else {
                    Log.w(TAG, "⚠️ Location permission not granted — skipping location fetch")
                }

                // Get fresh WiFi scan results using new WifiScanner
                Log.i(TAG, "📶 Fetching fresh WiFi list...")
                val wifiList = wifiScanner.getFreshWifiList()
                Log.i(TAG, "📶 WiFi scan complete: ${wifiList.size} networks found")

                // Create record with collected data
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
                    wifiService = wifiScanner.isWifiEnabled(),
                    dataService = Utils.isMobileDataEnabled(this@MyService),
                    notification = Utils.isNotificationPermissionGranted(this@MyService),
                    batterySaver = !Utils.isBatterySaverOn(this@MyService),
                    batteryOptimization = !Utils.isBatteryOptimizationOff(this@MyService),
                    wifi_list = wifiList
                )

                Log.i(TAG, "📝 Created record at ${record.localTime}")

                // ✅ Let AttendanceSyncManager handle:
                // - Validation (5-min boundary, gap checking, duplicates)
                // - Saving to local DB
                // - API sync (if internet available) or save only (if offline)
                // - Returns sync message via callback

                attendanceSyncManager.saveRecordLocally(record, user) { isStillInShift, syncMessage ->
                    if (isStillInShift) {
                        Log.i(TAG, "✅ Sync complete: $syncMessage")

                        // ✅ STEP 5: Schedule next alarm immediately
                        AlarmReceiver.scheduleNextAlignedAlarm(this@MyService)
                        Log.i(TAG, "⏰ Next alarm scheduled - service will wake up in 5 minutes")

                        // ✅ STEP 6: Update notification with sync message (dismissable)
                        val dismissableNotification = createNotification(syncMessage, isDismissable = true)
                        notificationManager?.notify(NOTIFICATION_ID, dismissableNotification)

                        // ✅ STEP 7: Auto-dismiss after 10 seconds
                        serviceScope.launch {
                            delay(10000) // 10 seconds to read
                            Log.i(TAG, "🗑️ Auto-dismissing notification")
                            notificationManager?.cancel(NOTIFICATION_ID)

                            // Stop service after notification dismissed
                            Log.i(TAG, "🛑 Stopping service")
                            finishServiceOperations()
                        }
                    } else {
                        Log.w(TAG, "⚠️ Off-shift detected: $syncMessage")
                        updateForegroundNotification(this@MyService, "Off-shift - Service stopping...")
                        AlarmReceiver.scheduleNextAlarmFromCurrentTime(this@MyService)
                        finishServiceOperations()
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error in handleCollectAndStop", e)
                updateForegroundNotification(this@MyService, "Error - retrying...")
                AlarmReceiver.scheduleNextAlignedAlarm(this@MyService)
                finishServiceOperations()
            }
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


    private fun startServiceInForeground() {
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
        startServiceInForeground()
        checkShiftAndScheduleTasks()

    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)

        try {
            Log.i(TAG, "📱 onTaskRemoved: App removed from recent apps")

            // ✅ Clean up receivers to prevent memory leaks
            unregisterReceivers()
            val user = SharedPref.getInstance(this)?.getUser()
            if (user != null && AlarmReceiver.isInsideShiftWindow(user)) {
                Log.i(TAG, "⏰ Service destroyed during shift - AlarmManager will handle next wake-up")
                // Ensure alarms are scheduled (they should already be, but just in case)
                AlarmReceiver.scheduleNextAlignedAlarm(this)
            } else {
                Log.i(TAG, "⏸️ Service destroyed outside shift - no action needed")
            }
            // ✅ NO RESTART NEEDED
            // AlarmManager will trigger next CALL_API automatically
            // This is the key to low-end device reliability
            Log.i(TAG, "✅ Resources cleaned up. Next alarm will handle wake-up.")

        } catch (e: Exception) {
            Log.e(TAG, "Error in onTaskRemoved", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false

        try {
            Log.i(TAG, "💀 onDestroy: Service being destroyed")

            // ✅ CRITICAL: Unregister receivers FIRST to prevent memory leaks
            unregisterReceivers()

            // ✅ Release wake lock
            releaseWakeLock()

            // ✅ NO EMERGENCY RESTART NEEDED
            // The alarm-driven pattern handles this:
            // - If service dies at 10:07, next alarm at 10:10 will wake it up
            // - This is MORE reliable than trying to restart immediately
            // - Especially on low-end devices (Mara/Mobicel)

            val user = SharedPref.getInstance(this)?.getUser()
            if (user != null && AlarmReceiver.isInsideShiftWindow(user)) {
                Log.i(TAG, "⏰ Service destroyed during shift - AlarmManager will handle next wake-up")
                // Ensure alarms are scheduled (they should already be, but just in case)
                AlarmReceiver.scheduleNextAlignedAlarm(this)
            } else {
                Log.i(TAG, "⏸️ Service destroyed outside shift - no action needed")
            }

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

        const val ACTION_COLLECT_AND_STOP = "COLLECT_AND_STOP" // 👈 New action for alarm-triggered work
    }


}

class ServiceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == "com.shiftsmart.plus.ACTION_FINISH") {
            (context as? MyService)?.finishAllData()
        }
    }
}
