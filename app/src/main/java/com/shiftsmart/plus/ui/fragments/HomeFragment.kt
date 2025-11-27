package com.shiftsmart.plus.ui.fragments

import android.Manifest
import android.app.Dialog
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat

import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.shiftsmart.plus.R
import com.shiftsmart.plus.database.DBDao
import com.shiftsmart.plus.database.IssueModel
import com.shiftsmart.plus.database.RecordModel
import com.shiftsmart.plus.database.ShiftSmartPlusDatabase
import com.shiftsmart.plus.databinding.CustomAlertDialogBinding
import com.shiftsmart.plus.databinding.FragmentHomeBinding
import com.shiftsmart.plus.databinding.LoadingDialogBinding
import com.shiftsmart.plus.enums.StatusEnum
import com.shiftsmart.plus.models.DataRequest
import com.shiftsmart.plus.models.ErrorModel
import com.shiftsmart.plus.models.WifiModel
import com.shiftsmart.plus.services.MyService
import com.shiftsmart.plus.services.LocationTrack
import com.shiftsmart.plus.ui.activities.MainActivity
import com.shiftsmart.plus.utils.BatteryOptimizationContract
import com.shiftsmart.plus.utils.ButtonActionEnum
import com.shiftsmart.plus.utils.Constants.MY_PERMISSIONS_REQUEST_LOCATION
import com.shiftsmart.plus.utils.Constants.MY_PERMISSIONS_REQUEST_NOTIFICATION
import com.shiftsmart.plus.utils.FingerprintHelper
import com.shiftsmart.plus.utils.PermissionHandler
import com.shiftsmart.plus.utils.Resource
import com.shiftsmart.plus.utils.SharedPref
import com.shiftsmart.plus.utils.Utils
import com.shiftsmart.plus.utils.Utils.getCurrentDateTime
import com.shiftsmart.plus.utils.Utils.isServiceRunning
import com.shiftsmart.plus.utils.WifiScanner
import com.shiftsmart.plus.viewmodels.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import androidx.core.content.PackageManagerCompat
import androidx.core.content.UnusedAppRestrictionsConstants
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.shiftsmart.plus.periodicAction.ShiftRestartAlarmManager
import com.shiftsmart.plus.utils.GpsStatusMonitor
import com.shiftsmart.plus.utils.ShiftUtils
import com.shiftsmart.plus.utils.Utils.toLocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Calendar
import java.util.Locale
import kotlin.toString

@AndroidEntryPoint
class HomeFragment : Fragment(), GpsStatusMonitor.GpsStatusListener {


    private val TAG = "HomeFragment"
    private lateinit var mBinding: FragmentHomeBinding

    private var mProgressDialog: Dialog? = null
    private lateinit var progressDialogBinding: LoadingDialogBinding
    val mainViewModel: MainViewModel by viewModels()

    @Inject
    lateinit var db: ShiftSmartPlusDatabase

    @Inject
    lateinit var locationTrack: LocationTrack

    @Inject
    lateinit var locationManager: LocationManager


    private lateinit var dao: DBDao

    @Inject
    lateinit var wifiScanner: WifiScanner


    private lateinit var permissionHandler: PermissionHandler

    private var btnStatus: String = ""

    private var isSyncPressed: Boolean = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissionHandler.handlePermissionsResult(permissions)
    }

    private lateinit var gpsStatusMonitor: GpsStatusMonitor


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val user = SharedPref.getInstance(requireContext())?.getUser()
        if (user != null) {
            ShiftRestartAlarmManager.scheduleNextShiftAlarm(requireContext(), user)
        }

        gpsStatusMonitor = GpsStatusMonitor(requireContext())
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Inflate the layout for this fragment
        mBinding = FragmentHomeBinding.inflate(inflater, container, false)

        Log.i(TAG, "onCreateView: ")
        return mBinding.root
    }


    private fun setAppVersion() {
        try {
            val packageInfo = requireContext().packageManager.getPackageInfo(
                requireContext().packageName,
                0
            )
            val versionName = packageInfo.versionName
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }

            // Display as "Version 1.0.0 (1)"
            mBinding.versionTv.text = getString(R.string.version_format, versionName, versionCode)

            // OR display just version name: "1.0.0"
            // mBinding.versionTv.text = versionName

        } catch (e: Exception) {
            Log.e(TAG, "Error getting app version: ${e.message}")
            mBinding.versionTv.text = "N/A"
        }
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Handle window insets for edge-to-edge display on newer devices
        ViewCompat.setOnApplyWindowInsetsListener(mBinding.headerLayout) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = systemBars.top)
            insets
        }

        dao = db.dbDao()


        // Set app version
        setAppVersion()
        // Initialize PermissionHandler
        // Initialize PermissionHandler with a callback
        permissionHandler = PermissionHandler(requireContext(), requireActivity()) {
            onPermissionsGranted() // Call this method when all permissions are granted
        }
        permissionHandler.registerPermissionLauncher(permissionLauncher)
        permissionHandler.checkPermissions()

        val user = SharedPref.getInstance(requireContext())?.getUser()
        user?.let {
            val userId = it._id
            dao.getAllLiveRecords(userId).observe(viewLifecycleOwner, Observer { records ->
                if (records.isNotEmpty()) {
                    mBinding.cacheStatusTv.text = records.size.toString()
                    mBinding.syncButton.visibility = View.VISIBLE
                } else {
                    mBinding.cacheStatusTv.text = "0"
                    mBinding.syncButton.visibility = View.GONE
                }
            })
        }
        setUpProgressDialog()
        setUpClickListeners()
        setUpObserver()

        setChecksData()

    }

    override fun onGpsStatusChanged(enabled: Boolean) {
        // Here you get live updates when GPS enabled/disabled
        // Update your UI or notify ViewModel etc.
        setChecksData()
        if (enabled) {
            // GPS is ON
            Log.d("HomeFragment", "GPS enabled")
            // update UI or notify user
        } else {
            // GPS is OFF
            Log.d("HomeFragment", "GPS disabled")
            // show alert or UI hint to enable GPS
        }
    }

    private fun setChecksData() {


        mBinding.statusTv.isSelected = true
        val user = SharedPref.getInstance(requireContext())?.getUser()

        user?.let {
            mBinding.greetingText.text = "${getString(R.string.hi)} ${it.name} ${it.surName}"
        }

        mBinding.internetStatusIcon.setImageResource(
            if (!Utils.isInternetAvailable(requireContext())) R.drawable.ic_not_check else R.drawable.ic_check
        )
        mBinding.wifiStatusIcon.setImageResource(
            if (!wifiScanner.isWifiEnabled()) R.drawable.ic_not_check else R.drawable.ic_check
        )
        mBinding.mobileDataStatusIcon.setImageResource(
            if (!Utils.isMobileDataEnabled(requireContext())) R.drawable.ic_not_check else R.drawable.ic_check
        )
        mBinding.locationServiceStatusIcon.setImageResource(
            if (!Utils.isGpsAndPermissionEnabled(requireContext())) R.drawable.ic_not_check else R.drawable.ic_check
        )
        mBinding.notificationStatusIcon.setImageResource(
            if (!Utils.isNotificationPermissionGranted(requireContext())) R.drawable.ic_not_check else R.drawable.ic_check
        )
        // battery saver should turn off

        mBinding.batterySaverStatusIcon.setImageResource(
            if (!Utils.isBatterySaverOn(requireContext())) R.drawable.ic_check else R.drawable.ic_not_check
        )

        // battery optimization should turn off
        mBinding.batteryOptimiztionStatusIcon.setImageResource(
            if (!Utils.isBatteryOptimizationOff(requireContext())) R.drawable.ic_check else R.drawable.ic_not_check
        )


        saveIssuesinDB()


    }
    private suspend fun checkAndUpdateIssue(
        key: String,
        condition: Boolean,
        title: String,
        solution: String,
        dao: DBDao
    ) {
        val user = SharedPref.getInstance(requireContext())?.getUser()

        if (condition) {
            dao.insertIssue(IssueModel(issueKey = key, userId = user?._id.toString(), issueTitle = title, solution = solution))
        } else {
            dao.deleteIssueByKey(key)
        }
    }


    private fun saveIssuesinDB() {

        val isInternetAvailable = Utils.isInternetAvailable(requireContext())

        val isWifiOn = wifiScanner.isWifiEnabled()
        val isMobileDataOn = Utils.isMobileDataEnabled(requireContext())

        lifecycleScope.launch {
            checkAndUpdateIssue(
                key = "internet_off",
                condition = !isInternetAvailable,
                title = "No signal or mobile/wifi connectivity",
                solution = "Move to a better signal area and reopen Shift Smart+ to sync offline data.",
                dao = dao
            )

            // Only show Wi-Fi off if mobile data is also off
            checkAndUpdateIssue(
                key = "wifi_off",
                condition = !isWifiOn,
                title = "Wi-Fi switched On",
                solution = "Make sure Wi-Fi is ON in settings > connections.",
                dao = dao
            )
            checkAndUpdateIssue(
                key = "mobile_data_off",
                condition = !isMobileDataOn,
                title = "Mobile data switched off",
                solution = "Make sure mobile data is ON in settings > connections.",
                dao = dao
            )
            checkAndUpdateIssue(
                key = "location_off",
                condition = !Utils.isGpsAndPermissionEnabled(requireContext()),
                title = "Location switched On",
                solution = "Go to Settings > Apps > Shift Smart+ > Permissions > location permission.",
                dao = dao
            )

            checkAndUpdateIssue(
                key = "notification_off",
                condition = !Utils.isNotificationPermissionGranted(requireContext()),
                title = "Notifications not allowed",
                solution = "Go to Settings > Apps > Shift Smart+ > Notifications > enable all notifications.",
                dao = dao
            )

            checkAndUpdateIssue(
                key = "battery_saver_on",
                condition = Utils.isBatterySaverOn(requireContext()),
                title = "Battery Saver switched On",
                solution = "Go to Settings > Battery, disable all battery savers and managers for Shift Smart+.",
                dao = dao
            )

            checkAndUpdateIssue(
                key = "battery_optimization_on",
                condition = Utils.isBatteryOptimizationOff(requireContext()),
                title = "Battery optimization Active",
                solution = "Go to Settings > Battery > App Standby Optimizer > Shift Smart+ > disable optimization.",
                dao = dao
            )


            checkAndUpdateIssue(
                key = "background_restricted",
                condition = Utils.isAppBackgroundRestricted(requireContext()),
                title = "App allowed to run in the Background",
                solution = "Go to Settings > Apps > Shift Smart+ > Background Usage > disable 'Put unused apps to sleep'.",
                dao = dao
            )


            lifecycleScope.launch {
                val disabled = !isAutoPauseDisabled(requireContext())
                Log.d("AutoRevoke", if (disabled) "Pause app activity is DISABLED" else "ENABLED")

                checkAndUpdateIssue(
                    key = "permissions_removed",
                    condition =disabled, // You need to implement this check
                    title = "App not running every 5min - Remove permissions if unused",
                    solution = "Go to Settings > Apps > Shift Smart+ > Permissions > disable 'Remove permissions if unused'.",
                    dao = dao
                )
            }


            checkAndUpdateIssue(
                key = "screen_lock_close",
                condition = Utils.isCloseAppAfterScreenLockEnabled(requireContext()), // Implement this check
                title = "Close App after screen is locked",
                solution = "Go to Settings > Battery > Close after screen lock > Shift Smart+ > disable.",
                dao = dao
            )

            lifecycleScope.launch {
                val isCacheIssue = isAppCacheIssueDetected(requireContext(), dao)

                checkAndUpdateIssue(
                    key = "app_cache_issue",
                    condition = isCacheIssue,
                    title = "All settings checked but still offline",
                    solution = "Go to Settings > Apps > Shift Smart+ > Storage > clear cache/data, then relogin.",
                    dao = dao
                )
            }

            // Repeat for other issues...
        }
    }

    suspend fun   isAutoPauseDisabled(context: Context): Boolean {
        // Await the ListenableFuture inside the coroutine
        val status: Int = withContext(Dispatchers.Default) {
            PackageManagerCompat.getUnusedAppRestrictionsStatus(context).await()
        }

        return when (status) {
            UnusedAppRestrictionsConstants.DISABLED -> true  // User has disabled auto-pause
            UnusedAppRestrictionsConstants.API_30,
            UnusedAppRestrictionsConstants.API_30_BACKPORT,
            UnusedAppRestrictionsConstants.API_31 -> false // Feature enabled
            else -> true // Treat errors or unknown as disabled
        }
    }

    suspend fun isAppCacheIssueDetected(context: Context, dao: DBDao): Boolean {
        val user = SharedPref.getInstance(context)?.getUser() ?: return false

        return withContext(Dispatchers.IO) {
            val issues = dao.getAllIssues(user._id)

            val requiredKeys = setOf(
                "battery_saver_on",
                "location_off",
                "internet_off",
                "background_restricted",
                "notification_off",
                "permissions_removed",
                "battery_optimization_on"
            )

            val issueKeys = issues.map { it.issueKey }.toSet()
            requiredKeys.all { issueKeys.contains(it) }
        }
    }

    // This method is called once all permissions are granted
    private fun onPermissionsGranted() {
        setChecksData()
    }

    override fun onResume() {
        super.onResume()
        Log.i(TAG, "onResume: HomeFragment")
        setChecksData()
    }

    override fun onStart() {
        super.onStart()
        gpsStatusMonitor.setListener(this)
        gpsStatusMonitor.startMonitoring()
    }

    override fun onStop() {
        super.onStop()
        gpsStatusMonitor.removeListener()
        gpsStatusMonitor.stopMonitoring()
    }

    private fun setUpProgressDialog(
    ) {
        if (mProgressDialog != null && mProgressDialog!!.isShowing) {
            return
        }
        val inflater = LayoutInflater.from(requireActivity())
        progressDialogBinding = LoadingDialogBinding.inflate(inflater)
        mProgressDialog = Dialog(requireContext())
        mProgressDialog?.setContentView(progressDialogBinding.root)
        mProgressDialog?.setCancelable(false)
        mProgressDialog?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

    }

    private fun setUpClickListeners() {

        mBinding.menuBtn.setOnClickListener {
            (activity as? MainActivity)?.toggleDrawer()
        }
        mBinding.errorsBtn.setOnClickListener {
            findNavController().navigate(R.id.action_homeFragment_to_errorsSolutionsFragment)
        }

//        mBinding.errorsBtn.setOnClickListener {
//            // Manually stop the service for testing WorkManager restart
//            Log.i(TAG, "🛑 MANUAL STOP: Stopping service to test WorkManager 15-min restart")
//
//            val serviceIntent = Intent(requireContext(), MyService::class.java)
//            requireContext().stopService(serviceIntent)
//
//            // Optional: Show a toast to confirm
//            Toast.makeText(
//                requireContext(),
//                "Service stopped. WorkManager should restart it within 15 minutes if inside shift window.",
//                Toast.LENGTH_LONG
//            ).show()
//
//            Log.i(TAG, "⏰ WorkManager will check and restart service within 15 minutes if shift is active")
//        }



        mBinding.syncButton.setOnClickListener {
            if (Utils.isInternetAvailable(requireContext())) {
                isSyncPressed = true
                showProgressDialog(getString(R.string.syncing_date))

                val user = SharedPref.getInstance(requireContext())?.getUser()

                user?.let { itit ->
                    Log.i(TAG, "callApiData: 617 internet available")
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            // Step 1: Fetch all records from the database

                            val savedIssues = dao.getAllIssues(itit._id)

                            val errorList = savedIssues.map {
                                ErrorModel(
                                    key = it.issueKey,
                                    title = it.issueTitle,
                                    solution = it.solution,
                                    time = Utils.getUTCFromTimestamp(it.timestamp)
                                )
                            }
                            val allRecords = dao.getAllRecords(itit._id).map { it.toDataRequest(errorList) }

                            // ✅ COMPREHENSIVE VALIDATION: Recheck gaps, fill missing records,
                            // remove invalid ones, and sort by local time in ascending order
//                            val validatedRecords = validateAndPrepareRecordsForSync(allRecords)

                            val token = SharedPref.getInstance(requireContext())?.getToken() ?: ""
                            Log.i(TAG, "callApiData: Sending ${allRecords.size} validated records to API")

                            // Switch to Main thread before updating LiveData
                            withContext(Dispatchers.Main) {
                                mainViewModel.sendAppData(allRecords, token, requireContext())
                            }
                        } catch (e: Exception) {
                            dismissProgressDialog()
                            Log.e(TAG, "Error fetching records: ${e.message}")
                        }
                    }
                }

            } else {
                showMessage(getString(R.string.no_network_connection))
            }
        }

        mBinding.arrivalBtn.setOnClickListener {
            performActionWithFingerprintCheck(requireActivity(), requireContext()) {
                // Fingerprint passed, proceed with your original code
               arrivalButtonPressed()
            }

//            CoroutineScope(Dispatchers.Main).launch {
//                val user = SharedPref.getInstance(requireContext())?.getUser()
//                user?.let {
////                    deleteRecordsInTimeRange(
////                        userId = it._id.toString(),
////                        startTime = "04:00:00",
////                        endTime = "08:00:00",
////                        date = "2025-10-30"
////                    )
//                    dao.deleteAllRecordsByUserId(it._id)
//                }
//            }


        }

        mBinding.departBtn.setOnClickListener {
            performActionWithFingerprintCheck(requireActivity(), requireContext()) {
                // Fingerprint passed, proceed with your original code
                departireButtonPressed()
            }
        }

    }

    /**
     * Deletes all records for a user within a specific time range on a given date.
     *
     * Example: Delete records from 04:00 to 08:00 on Wednesday Oct 29, 2025
     *
     * @param userId The user ID whose records to delete
     * @param startTime Start time in "HH:mm:ss" format (e.g., "04:00:00")
     * @param endTime End time in "HH:mm:ss" format (e.g., "08:00:00")
     * @param date The date in "yyyy-MM-dd" format (e.g., "2025-10-29")
     */
    /**
     * Deletes all records for a user within a specific time range on a given date.
     *
     * Example: Delete records from 04:00 to 08:00 on Wednesday Oct 29, 2025
     *
     * @param userId The user ID whose records to delete
     * @param startTime Start time in "HH:mm:ss" format (e.g., "04:00:00")
     * @param endTime End time in "HH:mm:ss" format (e.g., "08:00:00")
     * @param date The date in "yyyy-MM-dd" format (e.g., "2025-10-29")
     */
    suspend fun deleteRecordsInTimeRange(
        userId: String,
        startTime: String,
        endTime: String,
        date: String
    ) {
        try {
            Log.i(TAG, "🗑️ Deleting records for user $userId")
            Log.i(TAG, "   Date: $date")
            Log.i(TAG, "   Time range (UTC): $startTime to $endTime")

            withContext(Dispatchers.IO) {
                val allRecords = dao.getAllRecords(userId)

                val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                val utcFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault())
                utcFormatter.timeZone = java.util.TimeZone.getTimeZone("UTC")

                val startTimeParsed = timeFormatter.parse(startTime)
                val endTimeParsed = timeFormatter.parse(endTime)

                var deletedCount = 0

                allRecords.forEach { record ->
                    try {
                        // Parse the full UTC timestamp (format: "2025-10-29T02:05:00Z")
                        val utcDateTime = utcFormatter.parse(record.time)

                        if (utcDateTime != null && startTimeParsed != null && endTimeParsed != null) {
                            // Extract UTC date (before 'T')
                            val recordUtcDate = record.time.substringBefore('T')

                            // Extract UTC time (between 'T' and 'Z')
                            val recordUtcTimeStr = record.time.substringAfter('T').substringBefore('Z')
                            val recordUtcTime = timeFormatter.parse(recordUtcTimeStr)

                            // Check if record is on the target date (using UTC date)
                            val isOnTargetDate = recordUtcDate == date

                            // Check if UTC time is within the range
                            val isInTimeRange = if (recordUtcTime != null) {
                                val recordTimeMillis = recordUtcTime.time
                                recordTimeMillis >= startTimeParsed.time && recordTimeMillis < endTimeParsed.time
                            } else {
                                false
                            }

                            // Delete if both conditions match
                            if (isOnTargetDate && isInTimeRange) {
                                dao.deleteRecordByUuid(record.uuid)
                                deletedCount++
                                Log.d(TAG, "   ✅ Deleted: Local=${record.localTime} UTC=${record.time}")
                            }
                        }

                    } catch (e: Exception) {
                        Log.e(TAG, "   ❌ Error processing record ${record.time}: ${e.message}", e)
                    }
                }

                withContext(Dispatchers.Main) {
                    Log.i(TAG, "✅ Deletion complete: $deletedCount records deleted")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error deleting records in time range: ${e.message}", e)
        }
    }



    private fun arrivalButtonPressed() {
        btnStatus = StatusEnum.arrival.name
        setChecksData()
        if (locationTrack.checkLocationPermissions()) {
            locationTrack.stopListener()
            locationTrack.loc = null
            fetchLocationData()
        } else {
            checkandGrantLocationPermission()
        }
    }

    private fun departireButtonPressed() {
        btnStatus = StatusEnum.departure.name
        setChecksData()
        if (locationTrack.checkLocationPermissions()) {
            locationTrack.stopListener()
            locationTrack.loc = null
            fetchLocationData()
        } else {
            checkandGrantLocationPermission()
        }
    }


    fun performActionWithFingerprintCheck(activity: FragmentActivity, context: Context, buttonActionCall: () -> Unit) {
        if (!FingerprintHelper.isFingerprintSupported(context)) {
            buttonActionCall()
            return
        }

        if (!FingerprintHelper.isFingerprintEnabled(context)) {
            Toast.makeText(context,
                getString(R.string.fingerprint_not_enabled_in_app_settings), Toast.LENGTH_SHORT).show()
            return
        }

        if (!FingerprintHelper.isFingerprintAvailable(context)) {
            // Show dialog to enable fingerprint in device settings
            AlertDialog.Builder(context)
                .setTitle(getString(R.string.fingerprint_not_enabled))
                .setMessage(getString(R.string.please_enable_fingerprint_in_your_device_settings))
                .setPositiveButton(getString(R.string.go_to_settings)) { _, _ ->
                    FingerprintHelper.openSecuritySettings(context)
                }
                .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                    dialog.dismiss()  // just dismiss the dialog on cancel
                }
                .show()
            return
        }

        FingerprintHelper.authenticate(
            activity,
            onSuccess = { buttonActionCall() },
            onError = { err ->Utils.showSnackBar(err, mBinding.root) }
        )
    }

    private fun checkandGrantLocationPermission() {

        val dialog = Dialog(requireActivity())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(true)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val dialogBinding = CustomAlertDialogBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)
        dialog.show()

        dialogBinding.negativeBtn.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.postiveBtn.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
//                requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                requestMultiplePermissionsLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
//                        Manifest.permission.FOREGROUND_SERVICE_LOCATION
                    )
                )
            } else {

                if (locationTrack.checkLocationPermissions()) {
                    Log.i(TAG, "setupClickLiseteners:less then 33 permission granted")
                    fetchLocationData()
                } else {
                    Log.i(TAG, "setupClickLiseteners:less then 33 permission not granted")

                    requestPermissions(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        ),
                        MY_PERMISSIONS_REQUEST_LOCATION
                    )
                }

            }
            dialog.dismiss()
        }
    }

    private val requestMultiplePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
//        val foregroundLocationGranted = permissions[Manifest.permission.FOREGROUND_SERVICE_LOCATION] ?: false

        if (fineLocationGranted && coarseLocationGranted) {
            Log.i(TAG, "All permissions granted")

            fetchLocationData()
        } else {
            Log.i(TAG, "Some permissions not granted")

            Utils.showSnackBar(
                getString(R.string.location_permission_not_granted),
                mBinding.root
            )
        }
    }



    private fun showMessage(message: String) {

        mBinding.statusTv.text = message

    }

    private fun setUpObserver() {
        mainViewModel.sendDataResponse.observe(viewLifecycleOwner) { resource ->
            when (resource) {
                is Resource.Loading -> {

                }

                is Resource.Success -> {
                    val attendanceResponse = resource.data

                    // Get the main message from response
                    val mainMessage = attendanceResponse.message
                    Log.i(TAG, "setUpObserver: successResponse:${attendanceResponse}")
                    Log.i(TAG, "setUpObserver: mainMessage:${mainMessage}")

                    CoroutineScope(Dispatchers.Main).launch {
                        dismissProgressDialog()
                        if (isSyncPressed) {
                            showMessage(getString(R.string.data_sync_successfully_to_server))
                            isSyncPressed = false
                        }

                        // Check if main message requires deleting all user records
                        if (mainMessage.contains("Multiple attendance records", ignoreCase = true)) {

                            withContext(Dispatchers.Main) {
                                showMessage(mainMessage)
                            }

                            // Delete all records for this user
                            withContext(Dispatchers.IO) {
                                val user = SharedPref.getInstance(requireContext())?.getUser()
                                user?.let {
                                    val userId = it._id
                                    db.dbDao().deleteAllRecordsByUserId(userId)
                                    Log.i(TAG, "setUpObserver: Deleted all records for user: $userId")
                                }
                            }
                        }
                        else {
                            // Show main message if not related to deletion
                            if (mainMessage.isNotEmpty() && !isSyncPressed) {
                                withContext(Dispatchers.Main) {
                                    showMessage(mainMessage)
                                }
                            }
                        }

                        // Iterate through attendance data list
                        attendanceResponse.data.forEach { attendance ->
                            Log.i(TAG, "setUpObserver: eachResponse:${attendance}")
                            when (attendance.attendanceStatus) {
                                "online" -> {
                                    // If status is "online", show the message or store info
                                    withContext(Dispatchers.Main) {
                                        showMessage(attendance.message)
                                    }
                                    CoroutineScope(Dispatchers.IO).launch {
                                        val uuid = attendance.UUID // Get UUID from response
                                        // Find and delete records with this UUID
                                        db.dbDao().deleteRecordByUuid(uuid)
                                    }
                                }

                                "offline" -> {
                                    // If status is "offline", delete corresponding record from database
                                    CoroutineScope(Dispatchers.IO).launch {
                                        val uuid = attendance.UUID // Get UUID from response
                                        // Find and delete records with this UUID
                                        db.dbDao().deleteRecordByUuid(uuid)

                                    }
                                }
                            }
                        }
                    }
                }


                is Resource.Error -> {
                    CoroutineScope(Dispatchers.Main).launch {
                        dismissProgressDialog()
                    }
                    Log.i(TAG, "setUpObserver: error:${resource.message}")

                    if (resource.message == "LOGOUT") {
                        deleteUserDataAndLogout()
                    } else {
                        if (resource.message.contains("No address associated with hostname")) {
                            showMessage(getString(R.string.unable_to_connect_internet_right_now_please_try_again))
                        } else {
                            showMessage(resource.message)
                        }
                    }

                }

                else -> {}
            }
        }

    }

    fun showProgressDialog(message: String) {
        progressDialogBinding.titleTv.text = message
        if (mProgressDialog != null && mProgressDialog?.isShowing == false) {
            mProgressDialog?.show()
        }
    }

    fun updateProgressDialogMessage(message: String) {
        progressDialogBinding.titleTv.text = message
        if (mProgressDialog != null && mProgressDialog?.isShowing == false) {
            mProgressDialog?.show()
        }
    }

    fun dismissProgressDialog() {
        if (mProgressDialog != null && mProgressDialog?.isShowing == true) {
            mProgressDialog?.dismiss()
        }
    }

    private fun deleteUserDataAndLogout() {

        lifecycleScope.launch {
            locationTrack.stopListener()
            if (isServiceRunning(requireContext(), MyService::class.java)) {
                // Clear Notifications
                val notificationManager = requireActivity().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancelAll()
                Log.i("Service", "Service is running. Stopping it now.")
                requireContext().stopService(Intent(requireContext(), MyService::class.java))
            }

            SharedPref.getInstance(requireContext())?.clearLastSyncTime() // Clear sync timestamps
            SharedPref.getInstance(requireContext())?.clearPrefrence()
            findNavController().navigate(R.id.action_homeFragment_to_loginFragment)

        }
    }
    fun fetchLocationData() {
        val checkGPS = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val checkNetwork = Utils.isInternetAvailable(requireContext())

        Log.i(TAG, "fetchLocationData: checkNetwork:$checkNetwork --> checkGps:$checkGPS")

        if (checkGPS) {
            val locationTrack = LocationTrack(requireContext())
            val mLocationManager = requireActivity().getSystemService(Context.LOCATION_SERVICE) as LocationManager
            showProgressDialog(getString(R.string.fetching_location))

            var locationFetched = false

            // Start 30-second timeout
            CoroutineScope(Dispatchers.Main).launch {
                delay(30_000) // wait for 30 seconds
                if (!locationFetched) {
                    Log.i(TAG, "fetchLocationData: Timeout reached, location not fetched.")
                    showMessage(getString(R.string.unable_to_fetch_location_please_try_again_later))
                    dismissProgressDialog()
//                    callApiData(0.0, 0.0) // fallback
                }
            }

            // Try to get location
            locationTrack.getLocation(mLocationManager) { location ->
                if (location != null) {
                    locationFetched = true
                    locationTrack.stopListener()
                    locationTrack.loc = null
                    mBinding.coordsStatusTv.text = "${location.latitude} , ${location.longitude}"
                    mBinding.lastUpdateStatusTv.text = getCurrentDateTime().toString()

                    callApiData(location.latitude, location.longitude)
                    Log.i(TAG, "fetchLocationData: location not null: $location")
                } else {
                    Log.i(TAG, "fetchLocationData: location null")
                    // Let the timeout handle showing message & fallback
                }
            }
        } else {
            showAlert(ButtonActionEnum.GPS.name)
        }
    }


    var isLocationFetched = false

    fun callApiData(lat: Double, lan: Double) {
        isLocationFetched = false

        Log.i(TAG, "callApiData: 577 isLocationEnabled:${isLocationFetched}")

        mBinding.statusTv.text = ""
        try {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // Get fresh WiFi scan results using new WifiScanner
                    Log.i(TAG, "📶 Fetching fresh WiFi list...")
                    val wifiList = wifiScanner.getFreshWifiList()
                    Log.i(TAG, "📶 WiFi scan complete: ${wifiList.size} networks found")

                    withContext(Dispatchers.Main) {
                        if (wifiList.isNotEmpty()) {
                            displayWifiNetworks(wifiList)
                        }
                    }

                    if (!isLocationFetched) {
                        Log.i(TAG, "callApiData: 588 wifiList${wifiList}-->batterySAver:${Utils.isBatterySaverOn(requireContext())}--->optimization:${Utils.isBatteryOptimizationOff(requireContext())}")
                        if (isLocationFetched) {
                            Log.i(TAG, "callApiData: already in progress, exiting.")
                            return@launch
                        }

                        isLocationFetched = true
                        val user = SharedPref.getInstance(requireContext())?.getUser()
                        user?.let { itit ->
                            Log.i(TAG, "callApiData: 594user found")
                            val randomUid = Utils.generateRandomUuid()
                            val record = RecordModel(
                                uuid = randomUid,
                                user_id = itit._id,
                                lat = lat,
                                lng = lan,
                                localTime = Utils.getCurrent24HourTime(),
                                time = Utils.getCurrentUtcTime(),
                                attendanceType = btnStatus,
                                attendanceStatus = Utils.checkInternetAndSetStatus(requireContext()),
                                isForceAttendance = false,
                                isLocation = locationTrack.checkLocationPermissions(),
                                wifiService = wifiScanner.isWifiEnabled(),
                                dataService = Utils.isMobileDataEnabled(requireContext()),
                                notification = Utils.isNotificationPermissionGranted(requireContext()),
                                batterySaver = !Utils.isBatterySaverOn(requireContext()),
                                batteryOptimization = !Utils.isBatteryOptimizationOff(requireContext()),
                                wifi_list = wifiList
                            )
                            if (Utils.isInternetAvailable(requireContext())) {

                                // Handle saving or API call
                                withContext(Dispatchers.Main) {
                                    updateProgressDialogMessage(getString(R.string.saving_datato_server))
                                }

                                try {

                                    val savedIssues = dao.getAllIssues(itit._id)

                                    val errorList = savedIssues.map {
                                        ErrorModel(
                                            key = it.issueKey,
                                            title = it.issueTitle,
                                            solution = it.solution,
                                            time = Utils.getUTCFromTimestamp(it.timestamp)
                                        )
                                    }

                                    Log.i(TAG, "callApiDataTEstError: errorList:${errorList.size}")
                                    val allRecords = dao.getAllRecords(itit._id).map { it.toDataRequest(errorList) }

                                    // Add the new record to the list
                                    val allRecordsWithNew = allRecords + record.toDataRequest(errorList)

                                    Log.i(TAG, "callApiData: Total records before validation: ${allRecordsWithNew.size}")

                                    // ✅ COMPREHENSIVE VALIDATION: Recheck gaps, fill missing records,
                                    // remove invalid ones, and sort by local time in ascending order
//                                    val validatedRecords = validateAndPrepareRecordsForSync(allRecordsWithNew)

                                    val token = SharedPref.getInstance(requireContext())?.getToken() ?: ""
                                    withContext(Dispatchers.Main) {
                                        mainViewModel.sendAppData(allRecordsWithNew, token, requireContext())
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        dismissProgressDialog()
                                    }
                                    Log.e(TAG, "Error fetching records: ${e.message}")
                                }
                            } else {
                                Log.i(
                                    TAG,
                                    "callApiData: internet not available saving to database: ${record}"
                                )

                                withContext(Dispatchers.Main) {
                                    updateProgressDialogMessage(getString(R.string.saving_data_to_database))
                                    dismissProgressDialog()
                                }

                                // Save to database when no internet is available
//                                dao.deleteRecordByUuid("0216a876-472c-4767-889c-0bfd2ca86a6c")
//                                dao.deleteRecordByUuid("6664af52-682e-47ce-80c1-abe87d6ff0e3")
//                                dao.deleteRecordByUuid("eea9626a-e1d0-4776-96be-340247962e98")

                                dao.insertRecord(record)

                                withContext(Dispatchers.Main) {
                                    showMessage(getString(R.string.offile_alert_message))
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        dismissProgressDialog()
                    }
                    Log.e(TAG, "callApiData: exception: ${e.message}", e)
                } finally {
                    // Reset isLocationFetched when everything is done, so the method can be called again if needed
                    isLocationFetched = false
                }
            }
        } catch (e: Exception) {
            dismissProgressDialog()
            Log.i(TAG, "callApiData: 672 exception:${e.printStackTrace()}")
        }

    }


    ///////////////////////////////////////////////////

   /* var isLocationFetched = false

    fun callApiData(lat: Double, lan: Double) {
        isLocationFetched = false

        Log.i(TAG, "callApiData: 577 isLocationEnabled:${isLocationFetched}")

        mBinding.statusTv.text = ""
        try {
            var wifiList = listOf<WifiModel>()
            wifiScanner.scanWifiNetworks { scanResults ->
                wifiList = if (scanResults.isNotEmpty()) {
                    displayWifiNetworks(scanResults)
                    scanResults.map { result ->
                        WifiModel(
                            ssid = result.SSID,
                            bssid = result.BSSID,
                            strength = rssiToPercentage(result.level)
                        )
                    }
                } else {
                    arrayListOf()
                }

                if (!isLocationFetched)
                {

                    Log.i(TAG, "callApiData: 588 wifiList${wifiList}-->batterySAver:${ Utils.isBatterySaverOn(requireContext())}--->optimization:${Utils.isBatteryOptimizationOff(requireContext())}")
                    if (isLocationFetched) {
                        Log.i(TAG, "callApiData: already in progress, exiting.")
                        return@scanWifiNetworks
                    }

                    isLocationFetched=true
                    val user = SharedPref.getInstance(requireContext())?.getUser()
                    user?.let { itit ->
                        Log.i(TAG, "callApiData: 594user found")
                        val randomUid = Utils.generateRandomUuid()
                        val record = RecordModel(
                            uuid = randomUid,
                            user_id = itit._id.toString(),
                            lat = lat,
                            lng = lan,
                            localTime = Utils.getCurrent24HourTime(),
                            time = Utils.getCurrentUtcTime(),
                            attendanceType = btnStatus,
                            attendanceStatus = Utils.checkInternetAndSetStatus(requireContext()),
                            isForceAttendance = false,
                            isLocation = locationTrack.checkLocationPermissions(),
                            wifiService = wifiScanner.isWifiEnabled(),
                            dataService = Utils.isMobileDataEnabled(requireContext()),
                            notification = Utils.isNotificationPermissionGranted(requireContext()),
                            batterySaver = !Utils.isBatterySaverOn(requireContext()),
                            batteryOptimization = !Utils.isBatteryOptimizationOff(requireContext()),
                            wifi_list = wifiList
                        )
                        if (Utils.isInternetAvailable(requireContext())) {

                            // Handle saving or API call
                            updateProgressDialogMessage(getString(R.string.saving_datato_server))
                            CoroutineScope(Dispatchers.IO).launch {
                                try {

                                    val savedIssues = dao.getAllIssues(user?._id.toString()) // List<IssueEntity>

                                    val errorList = savedIssues.map {
                                        ErrorModel(
                                            key = it.issueKey,
                                            title = it.issueTitle,
                                            solution = it.solution,
                                            time = Utils.getUTCFromTimestamp(it.timestamp)
                                        )
                                    }

                                    Log.i(TAG, "callApiDataTEstError: errorList:${errorList.size}")
                                    val allRecords = dao.getAllRecords(user._id.toString())
                                        .map { it.toDataRequest(errorList) }

                                    // Add the new record to the list
                                    val allRecordsWithNew = allRecords + record.toDataRequest(errorList)

                                    Log.i(TAG, "callApiData: Total records before validation: ${allRecordsWithNew.size}")

                                    // ✅ COMPREHENSIVE VALIDATION: Recheck gaps, fill missing records,
                                    // remove invalid ones, and sort by local time in ascending order
                                    val validatedRecords = validateAndPrepareRecordsForSync(allRecordsWithNew)

                                    val token = SharedPref.getInstance(requireContext())?.getToken() ?: ""
                                    withContext(Dispatchers.Main) {
                                        mainViewModel.sendAppData(validatedRecords, token, requireContext())
                                    }
                                } catch (e: Exception) {
                                    dismissProgressDialog()
                                    Log.e(TAG, "Error fetching records: ${e.message}")
                                }
                            }
                        }
                        else {
                            Log.i(
                                TAG,
                                "callApiData: internet not available saving to database: ${record}"
                            )
                            updateProgressDialogMessage(getString(R.string.saving_data_to_database))

                            dismissProgressDialog()

                            // Save to database when no internet is available - Create 20 dummy records
                            CoroutineScope(Dispatchers.IO).launch {
                                dao.deleteAllRecords();

                                val dummyRecords = mutableListOf<RecordModel>()

                                // Dummy 1-19: Various scenarios with different WiFi, permissions, locations
                                for (i in 1..19) {
                                    val attendanceTypes = listOf("default", "default")
                                    val wifiConfigs = listOf(
                                        emptyList(),
                                        listOf(WifiModel(ssid = "Office-WiFi-$i", bssid = "AA:BB:CC:DD:EE:${i.toString().padStart(2, '0')}", strength = 85)),
                                        listOf(
                                            WifiModel(ssid = "Network-A-$i", bssid = "11:22:33:44:55:${i.toString().padStart(2, '0')}", strength = 90),
                                            WifiModel(ssid = "Network-B-$i", bssid = "11:22:33:44:66:${i.toString().padStart(2, '0')}", strength = 70)
                                        ),
                                        listOf(
                                            WifiModel(ssid = "WiFi-1-$i", bssid = "AA:11:BB:22:CC:${i.toString().padStart(2, '0')}", strength = 75),
                                            WifiModel(ssid = "WiFi-2-$i", bssid = "AA:11:BB:22:DD:${i.toString().padStart(2, '0')}", strength = 55),
                                            WifiModel(ssid = "WiFi-3-$i", bssid = "AA:11:BB:22:EE:${i.toString().padStart(2, '0')}", strength = 40)
                                        )
                                    )

                                    // Time intervals: mostly 5 minutes, but some records have irregular gaps
                                    // Calculate cumulative minutes back from current time
                                    val minuteIncrements = listOf(
                                        5, 5, 5, 5, 5, 5,      // Records 1-6: regular 5-min intervals
                                        3,                      // Record 7: 3-min gap (e.g., 8:30 → 8:33)
                                        2,                      // Record 8: 2-min gap (e.g., 8:33 → 8:35)
                                        5, 5,                   // Records 9-10: regular 5-min intervals
                                        3,                      // Record 11: 3-min gap
                                        2,                      // Record 12: 2-min gap
                                        5, 5, 5,               // Records 13-15: regular 5-min intervals
                                        4,                      // Record 16: 4-min gap
                                        1,                      // Record 17: 1-min gap
                                        5, 5                    // Records 18-19: regular 5-min intervals
                                    )

                                    val calendar = java.util.Calendar.getInstance()
                                    // Calculate total minutes back: sum of increments from position i to end
                                    var totalMinutesBack = 0
                                    for (j in i..19) {
                                        totalMinutesBack += minuteIncrements[j - 1]
                                    }
                                    calendar.add(java.util.Calendar.MINUTE, -totalMinutesBack)

                                    val localTimeFormat = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                                    val utcTimeFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.getDefault())
                                    utcTimeFormat.timeZone = java.util.TimeZone.getTimeZone("UTC")

                                    val localTimeWithOffset = localTimeFormat.format(calendar.time)
                                    val utcTimeWithOffset = utcTimeFormat.format(calendar.time)

                                    dummyRecords.add(RecordModel(
                                        uuid = Utils.generateRandomUuid(),
                                        user_id = itit._id.toString(),
                                        lat = lat + (i * 0.0001) - 0.001,
                                        lng = lan + (i * 0.0001) - 0.001,
                                        localTime = localTimeWithOffset,
                                        time = utcTimeWithOffset,
                                        attendanceType = attendanceTypes[i % 2],
                                        attendanceStatus = "offline",
                                        isForceAttendance = i % 5 == 0,
                                        isLocation = i % 3 != 0,
                                        wifiService = i % 4 != 0,
                                        dataService = i % 3 != 0,
                                        notification = i % 5 != 0,
                                        batterySaver = i % 2 == 0,
                                        batteryOptimization = i % 3 == 0,
                                        wifi_list = wifiConfigs[i % wifiConfigs.size]
                                    ))
                                }

                                // Dummy 20: Actual current record
                                dummyRecords.add(record)

                                // Insert all 20 records
                                dummyRecords.forEach { dummyRecord ->
                                    dao.insertRecord(dummyRecord)
                                }

                                Log.i(TAG, "callApiData: ${dummyRecords.size} dummy records created and saved")

                                withContext(Dispatchers.Main) {
                                    showMessage(getString(R.string.offile_alert_message) + " (${dummyRecords.size} records)")
                                }
                            }

                        }
                    }

                }

            }
        } catch (e: Exception) {
            dismissProgressDialog()
            Log.i(TAG, "callApiData: 672 exception:${e.printStackTrace()}")
        }finally {
            // Reset isLocationFetched when everything is done, so the method can be called again if needed
            isLocationFetched = false
        }

    }*/

    ///////////////////////////////////////////////

    private var clearTextHandler: Handler? = null
    private var clearTextRunnable: Runnable? = null

    private fun displayWifiNetworks(wifiList: List<WifiModel>) {
        val wifiInfo = StringBuilder()

        for (wifi in wifiList) {
            if (wifi.ssid.isNotEmpty()) { // Check if SSID is not empty
                wifiInfo.append("SSID: ${wifi.ssid}, BSSID: ${wifi.bssid}, Signal Strength: ${wifi.strength}%\n")
            }
        }

        mBinding.ssidTv.text = wifiInfo.toString()
        Log.i(TAG, "displayWifiNetworks: $wifiInfo")

        // Cancel the previous runnable if it exists
        clearTextRunnable?.let { clearTextHandler?.removeCallbacks(it) }

        // Create new Handler and Runnable
        clearTextHandler = Handler(Looper.getMainLooper())
        clearTextRunnable = Runnable {
            mBinding.ssidTv.text = "" // Clear the text after 5 seconds
        }

        // Post the runnable with a 5-second delay
        clearTextHandler?.postDelayed(clearTextRunnable!!, 5000)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Remove any pending handler callbacks
        clearTextRunnable?.let { clearTextHandler?.removeCallbacks(it) }
        clearTextHandler = null
        clearTextRunnable = null
    }
    fun showAlert(type:String) {

        val dialog = Dialog(requireActivity())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(true)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val dialogBinding = CustomAlertDialogBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)
        dialog.show()
//        enableButtons()
        when(type)
        {
            ButtonActionEnum.GPS.name ->{

                dialogBinding.postiveBtn.text=getString(R.string.yes)
                dialogBinding.negativeBtn.text=getString(R.string.no)

                dialogBinding.titleTv.text=getString(R.string.gps_is_not_enabled)
                dialogBinding.desTv.text=getString(R.string.do_you_want_to_turn_on_gps)
                dialogBinding.negativeBtn.setOnClickListener {
                    dialog.dismiss()
                }
                dialogBinding.postiveBtn.setOnClickListener {
                    val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                    startActivity(intent)
                    dialog.dismiss()
                }

            }
            ButtonActionEnum.INTERNET.name ->{

                dialogBinding.postiveBtn.text=getString(R.string.yes)
                dialogBinding.negativeBtn.text=getString(R.string.no)

                dialogBinding.titleTv.text=getString(R.string.no_network_connection)
                dialogBinding.desTv.text=getString(R.string.do_you_want_to_turn_on_network)
                dialogBinding.negativeBtn.setOnClickListener { dialog.dismiss() }
                dialogBinding.postiveBtn.setOnClickListener {
                    val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS)
                    startActivity(intent)
                    dialog.dismiss()
                }

            }
            ButtonActionEnum.SERVICE.name ->{

                dialogBinding.postiveBtn.text=getString(R.string.allow)
                dialogBinding.negativeBtn.text=getString(R.string.deny)

                dialogBinding.titleTv.text=getString(R.string.disable_battery_optimization)
                dialogBinding.desTv.text=getString(R.string.allow_app_to_run_in_backgorund)
                dialogBinding.negativeBtn.setOnClickListener { dialog.dismiss() }
                dialogBinding.postiveBtn.setOnClickListener {
                    batteryOptimizationLauncher.launch(Unit)
                    dialog.dismiss()
                }

            }
            ButtonActionEnum.NOTIFICATION.name ->{
                dialogBinding.postiveBtn.text=getString(R.string.allow)
                dialogBinding.negativeBtn.text=getString(R.string.deny)

                dialogBinding.titleTv.text=getString(R.string.post_notification)
                dialogBinding.desTv.text=getString(R.string.please_allow_enable_post_notification)
                dialogBinding.negativeBtn.setOnClickListener {
                    dialog.dismiss()
                }
                dialogBinding.postiveBtn.setOnClickListener {
                    getNotificationPermission()
                    dialog.dismiss()
                }

            }
        }

    }

    private val batteryOptimizationLauncher = registerForActivityResult(BatteryOptimizationContract()) { isSuccess ->
        if (Utils.isIgnoringBatteryOptimizations(requireContext()))
        {
            Log.i(TAG, "battery_optimization is ignored: ")

        } else {
            Log.i(TAG, "battery_optimization is not ignored: ")

            // Battery optimization not disabled
            Utils.showSnackBar(getString(R.string.please_turn_off_battery_optimization_to_allow_run_app_in_background),mBinding.root)

        }
    }

    fun getNotificationPermission() {
        try {
            if (Build.VERSION.SDK_INT > 32) {
                ActivityCompat.requestPermissions(
                    requireActivity(), arrayOf<String>(Manifest.permission.POST_NOTIFICATIONS),
                    MY_PERMISSIONS_REQUEST_NOTIFICATION
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
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

    /**
     * Extracts UTC time in HH:mm format (without seconds) from a full UTC timestamp.
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

    /**
     * Calculates the difference in minutes between two UTC time strings in HH:mm format.
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
     * Creates dummy records to fill a gap between two records during API sync validation.
     * Only creates records for times that fall within actual shift periods (not off-shift gaps).
     */
    private fun createDummyRecordsForGap(
        previousRecord: DataRequest,
        nextRecord: DataRequest,
        numberOfRecords: Int
    ): List<RecordModel> {
        val dummyRecords = mutableListOf<RecordModel>()

        try {
            // ✅ Get user and shift schedule for validation
            val user = SharedPref.getInstance(requireContext())?.getUser()
            if (user == null) {
                Log.e(TAG, "❌ No user found, cannot validate shift times")
                return emptyList()
            }

            val today = LocalDate.now()
            val activeMulti = user.multipleTimeTables?.find { mt ->
                val s = mt.startDate.toLocalDate()
                val e = mt.endDate.toLocalDate()
                today in s..e
            }
            val effectiveRange = activeMulti?.timetable?.range ?: user.timetable?.range

            if (effectiveRange == null) {
                Log.e(TAG, "❌ No timetable found, cannot validate shift times")
                return emptyList()
            }

            val utcFormatter = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.getDefault())
            utcFormatter.timeZone = java.util.TimeZone.getTimeZone("UTC")

            val prevUtcTime = utcFormatter.parse(previousRecord.time) ?: return emptyList()

            val localFormatter = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            val prevLocalTime = localFormatter.parse(previousRecord.localTime) ?: return emptyList()

            var skippedOffShift = 0

            for (i in 1..numberOfRecords) {
                val utcCalendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
                utcCalendar.time = prevUtcTime
                utcCalendar.add(java.util.Calendar.MINUTE, i * 5)
                val dummyUtcTime = utcFormatter.format(utcCalendar.time)

                val localCalendar = java.util.Calendar.getInstance()
                localCalendar.time = prevLocalTime
                localCalendar.add(java.util.Calendar.MINUTE, i * 5)
                val dummyLocalTime = localFormatter.format(localCalendar.time)

                // ✅ CRITICAL: Check if this time falls within ANY shift period
                val todayName = localCalendar.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.ENGLISH) ?: ""
                val yesterdayCalendar = localCalendar.clone() as Calendar
                yesterdayCalendar.add(Calendar.DAY_OF_YEAR, -1)
                val yesterdayName = yesterdayCalendar.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.ENGLISH) ?: ""

                val todayShift = effectiveRange.find { it.day.equals(todayName, ignoreCase = true) }
                val yesterdayShift = effectiveRange.find { it.day.equals(yesterdayName, ignoreCase = true) }

                var isWithinShift = false

                // Check today's shift
                if (todayShift?.start != null && todayShift.end != null) {
                    isWithinShift = ShiftUtils.isTimeWithinBufferRange(localCalendar, todayShift.start, todayShift.end)
                }

                // If not in today's shift, check yesterday's overnight shift
                if (!isWithinShift && yesterdayShift?.start != null && yesterdayShift.end != null) {
                    isWithinShift = ShiftUtils.isTimeWithinBufferRange(localCalendar, yesterdayShift.start, yesterdayShift.end, -1)
                }

                if (!isWithinShift) {
                    Log.i(TAG, "⏭️ Skipping dummy at $dummyLocalTime - OUTSIDE shift period (off-shift time)")
                    skippedOffShift++
                    continue
                }

                val dummyRecord = RecordModel(
                    uuid = Utils.generateRandomUuid(),
                    user_id = nextRecord.user_id,
                    lat = nextRecord.lat,
                    lng = nextRecord.lng,
                    localTime = dummyLocalTime,
                    time = dummyUtcTime,
                    attendanceType = "default",
                    attendanceStatus = nextRecord.attendanceStatus,
                    isForceAttendance = false,
                    isLocation = nextRecord.isLocation,
                    wifiService = nextRecord.wifiService,
                    dataService = nextRecord.dataService,
                    notification = nextRecord.notification,
                    batterySaver = nextRecord.batterySaver,
                    batteryOptimization = nextRecord.batteryOptimization,
                    wifi_list = nextRecord.wifi_list
                )

                dummyRecords.add(dummyRecord)
            }

            if (skippedOffShift > 0) {
                Log.i(TAG, "📊 Gap filling: Created ${dummyRecords.size} records, skipped $skippedOffShift off-shift times")
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error creating dummy records: ${e.message}", e)
        }

        return dummyRecords
    }

    /**
     * Comprehensive validation for API sync - rechecks gaps, fills missing records,
     * removes invalid ones, and sorts by local time.
     */
    private suspend fun validateAndPrepareRecordsForSync(
        allRecords: List<DataRequest>
    ): List<DataRequest> {
        Log.i(TAG, "🔍 STARTING COMPREHENSIVE VALIDATION - Total records: ${allRecords.size}")

        // STEP 1: Sort all records by local time (ascending order)
        val sortedRecords = allRecords.sortedBy {
            try {
                Utils.parseFlexibleTime(it.localTime) ?: LocalTime.MIN
            } catch (e: Exception) {
                LocalTime.MIN
            }
        }

        Log.i(TAG, "📋 Sorted records by local time")

        // STEP 2: Separate default and non-default records
        val defaultRecords = sortedRecords.filter { it.attendanceType == "default" }
        val nonDefaultRecords = sortedRecords.filter { it.attendanceType != "default" }

        Log.i(TAG, "📊 Default records: ${defaultRecords.size}, Non-default records: ${nonDefaultRecords.size}")

        // STEP 3: Validate and fix default records
        val validatedDefaultRecords = mutableListOf<DataRequest>()
        val recordsToDelete = mutableListOf<String>()
        val recordsToAdd = mutableListOf<RecordModel>()

        for (i in defaultRecords.indices) {
            val currentRecord = defaultRecords[i]

            try {
                if (i == 0) {
                    validatedDefaultRecords.add(currentRecord)
                    Log.d(TAG, "✅ First default record: ${currentRecord.localTime}")
                    continue
                }

                val previousRecord = validatedDefaultRecords.last()

                val prevUtcTimeHHMM = getUtcTimeHHMM(previousRecord.time)
                val currUtcTimeHHMM = getUtcTimeHHMM(currentRecord.time)

                val minutesDiff = calculateMinutesDifferenceHHMM(prevUtcTimeHHMM, currUtcTimeHHMM)

                Log.d(TAG, "⏱️ Comparing: ${previousRecord.localTime} (UTC: $prevUtcTimeHHMM) -> ${currentRecord.localTime} (UTC: $currUtcTimeHHMM) = $minutesDiff min")

                if (minutesDiff == 5) {
                    validatedDefaultRecords.add(currentRecord)
                    Log.d(TAG, "✅ Perfect 5-min interval: ${currentRecord.localTime}")

                } else if (minutesDiff > 5 && minutesDiff % 5 == 0) {
                    val numberOfDummyRecords = (minutesDiff / 5) - 1
                    Log.i(TAG, "⚠️ Gap detected: $minutesDiff minutes. Inserting $numberOfDummyRecords dummy records")

                    val dummyRecords = createDummyRecordsForGap(previousRecord, currentRecord, numberOfDummyRecords)
                    recordsToAdd.addAll(dummyRecords)

                    dummyRecords.forEach { dummy ->
                        validatedDefaultRecords.add(dummy.toDataRequest())
                        Log.d(TAG, "✅ Dummy record added: ${dummy.localTime}")
                    }

                    validatedDefaultRecords.add(currentRecord)
                    Log.d(TAG, "✅ Current record added after gap fill: ${currentRecord.localTime}")

                } else {
                    Log.e(TAG, "❌ Invalid interval: $minutesDiff min (not a multiple of 5) - DELETING ${currentRecord.localTime}, UUID: ${currentRecord.UUID}")
                    recordsToDelete.add(currentRecord.UUID)
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error processing record ${currentRecord.localTime}: ${e.message}", e)
                recordsToDelete.add(currentRecord.UUID)
            }
        }

        // STEP 4: Insert dummy records into database
        if (recordsToAdd.isNotEmpty()) {
            Log.i(TAG, "💾 Inserting ${recordsToAdd.size} dummy records into database")
            withContext(Dispatchers.IO) {
                recordsToAdd.forEach { dummy ->
                    try {
                        // ✅ CHECK: Does a record already exist at this UTC HH:mm?
                        val utcTimeHHMM = getUtcTimeHHMM(dummy.time)
                        val existingCount = dao.countRecordByUtcTimeHHMM(dummy.user_id, utcTimeHHMM)

                        if (existingCount > 0) {
                            Log.d(TAG, "⏭️ Skipping dummy at UTC $utcTimeHHMM - record already exists")
                        } else {
                            dao.insertRecord(dummy)
                            Log.d(TAG, "✅ Dummy saved to DB: ${dummy.localTime} (UTC: ${dummy.time})")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Failed to insert dummy record: ${e.message}")
                    }
                }
            }
        }

        // STEP 5: Delete invalid records from database
        if (recordsToDelete.isNotEmpty()) {
            Log.i(TAG, "🗑️ Deleting ${recordsToDelete.size} invalid records from database")
            withContext(Dispatchers.IO) {
                recordsToDelete.forEach { uuid ->
                    try {
                        dao.deleteRecordByUuid(uuid)
                        Log.d(TAG, "✅ Deleted record UUID: $uuid")
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Failed to delete record: ${e.message}")
                    }
                }
            }
        }

        // STEP 6: Combine validated default records with non-default records
        val finalRecords = mutableListOf<DataRequest>()
        finalRecords.addAll(validatedDefaultRecords)
        finalRecords.addAll(nonDefaultRecords)

        // STEP 7: Sort final list by local time (ascending order)
        val sortedFinalRecords = finalRecords.sortedBy {
            try {
                Utils.parseFlexibleTime(it.localTime) ?: LocalTime.MAX
            } catch (e: Exception) {
                LocalTime.MAX
            }
        }

        Log.i(TAG, "📤 VALIDATION COMPLETE:")
        Log.i(TAG, "   - Original records: ${allRecords.size}")
        Log.i(TAG, "   - Dummy records added: ${recordsToAdd.size}")
        Log.i(TAG, "   - Invalid records deleted: ${recordsToDelete.size}")
        Log.i(TAG, "   - Final records to send: ${sortedFinalRecords.size}")
        Log.i(TAG, "   - Records sorted by local time in ascending order ✅")

        sortedFinalRecords.forEachIndexed { index, record ->
            Log.d(TAG, "   [$index] ${record.localTime} - ${record.attendanceType} - UTC: ${getUtcTimeHHMM(record.time)}")
        }

        return sortedFinalRecords
    }

}

