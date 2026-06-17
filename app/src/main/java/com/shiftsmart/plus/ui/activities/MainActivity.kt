package com.shiftsmart.plus.ui.activities

import android.Manifest
import android.app.AlarmManager
import android.app.Dialog
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.navOptions
import com.google.android.material.snackbar.Snackbar
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.firebase.messaging.FirebaseMessaging
import com.shiftsmart.plus.R
import com.shiftsmart.plus.databinding.ActivityMainBinding
import com.shiftsmart.plus.databinding.LoadingDialogBinding
import com.shiftsmart.plus.databinding.LogoutDialogBinding
import com.shiftsmart.plus.periodicAction.AlarmScheduler
import com.shiftsmart.plus.periodicAction.PeriodicSyncWorkerManager
import com.shiftsmart.plus.periodicAction.RestartWatchdogManager
import com.shiftsmart.plus.periodicAction.ServiceHealthWorkerManager
import com.shiftsmart.plus.periodicAction.ShiftRestartAlarmManager
import com.shiftsmart.plus.services.LocationTrack
import com.shiftsmart.plus.services.MyService
import com.shiftsmart.plus.utils.DialogManager
import com.shiftsmart.plus.utils.FingerprintHelper
import com.shiftsmart.plus.utils.FullScreenIntentPermissionHelper
import com.shiftsmart.plus.utils.Resource
import com.shiftsmart.plus.utils.SessionLogoutCoordinator
import com.shiftsmart.plus.utils.SharedPref
import com.shiftsmart.plus.utils.Utils
import com.shiftsmart.plus.utils.Utils.isServiceRunning
import com.shiftsmart.plus.viewmodels.MainViewModel
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_COMPLAINT_CHECK = "com.shiftsmart.plus.EXTRA_COMPLAINT_CHECK"
        const val ACTION_PERFORM_COMPLAINT_CHECK = "com.shiftsmart.plus.ACTION_PERFORM_COMPLAINT_CHECK"
    }
    private lateinit var activityResultLauncher: ActivityResultLauncher<IntentSenderRequest>
    private lateinit var appUpdateManager: AppUpdateManager

    private val PERMISSIONS_REQUEST_CODE = 100
    private  val TAG = "MainActivityPLUS"
    private  val REQUEST_IGNORE_BATTERY_OPTIMIZATIONS = 1001

    // State management to prevent dialog overlap
    private var isUpdateDialogShowing = false
    private var isUpdateAvailable = false
    private var isFullScreenIntentDialogShowing = false
    private var isBatteryOptimizationDialogShowing = false
    private var isLogoutDialogShowing = false  // ✅ Track forced logout dialogs

    lateinit var drawerLayout: DrawerLayout
    private lateinit var mBinding:ActivityMainBinding
    private var logoutDialog: Dialog? = null  // Keep reference to avoid multiple dialogs
    private var forceLogoutDialog: AlertDialog? = null
    private var isForceLogoutReceiverRegistered = false

    @Inject
    lateinit var locationTrack: LocationTrack
    private var mProgressDialog: Dialog? = null
    private lateinit var progressDialogBinding: LoadingDialogBinding


    val mainViewModel: MainViewModel by viewModels()

    private val forceLogoutReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Prefer consuming any pending message first (atomic). If none, fallback to intent extra.
            val consumedMessage = SessionLogoutCoordinator.consumePendingMessage()
            val intentMessage = intent?.getStringExtra(SessionLogoutCoordinator.EXTRA_MESSAGE)
            val message = consumedMessage ?: intentMessage

            if (message != null) {
                // If we're already at the login screen, showing the dialog is acceptable (user hasn't seen Home dialog)
                val navHost = try { findNavController(R.id.nav_host_fragment) } catch (e: Exception) { null }
                val currentDestId = navHost?.currentDestination?.id
                val isAtLogin = currentDestId == R.id.loginFragment

                Log.i(TAG, "MainActivity forceLogoutReceiver: received force-logout (isAtLogin=$isAtLogin): $message")
                showForceLogoutDialog(message)
            } else {
                Log.i(TAG, "MainActivity forceLogoutReceiver: no pending force-logout message to handle")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize app update manager - SINGLE INSTANCE
        appUpdateManager = AppUpdateManagerFactory.create(this)

        // Register activity result launcher for update flow
        activityResultLauncher = registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            when (result.resultCode) {
                RESULT_OK -> {
                    Log.i(TAG, "✅ Update flow completed successfully")
                    isUpdateDialogShowing = false
                    // After successful update, app will restart automatically
                }
                RESULT_CANCELED -> {
                    Log.w(TAG, "⚠️ User cancelled update")
                    isUpdateDialogShowing = false
                    // For IMMEDIATE updates, user cannot use app without updating
                    showUpdateRequiredDialog()
                }
                else -> {
                    Log.e(TAG, "❌ Update flow failed! Result code: ${result.resultCode}")
                    isUpdateDialogShowing = false
                    showUpdateRequiredDialog()
                }
            }
        }

        mBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(mBinding.root)
        handleComplaintIntent(intent)
        registerForceLogoutReceiver()

        drawerLayout = mBinding.drawerLayout
        Log.i(TAG, "onCreate: Activity created")

        // ✅ Register progress dialog checker with DialogManager
        DialogManager.setProgressDialogChecker {
            mProgressDialog?.isShowing ?: false
        }

        // Check for app updates FIRST (before showing any other dialogs)
        checkForAppUpdates()

        startPermissionAction()
        setupDrawer()
        setupDrawerDestinationState()
        setUpProgressDialog()

        setupObserver()

    }


    /**
     * Check for app updates using Google Play In-App Update API
     * Uses IMMEDIATE update type - blocks app usage until updated
     */
    private fun checkForAppUpdates() {
        Log.i(TAG, "🔍 Checking for app updates...")

        appUpdateManager.appUpdateInfo.addOnSuccessListener { appUpdateInfo ->
            // ✅ ENHANCED LOGGING - Debug update status
            Log.i(TAG, "========== APP UPDATE CHECK ==========")
            Log.i(TAG, "📱 Current Version Code: ${packageManager.getPackageInfo(packageName, 0).versionCode}")
            Log.i(TAG, "📱 Current Version Name: ${packageManager.getPackageInfo(packageName, 0).versionName}")
            Log.i(TAG, "🔍 Update Availability: ${appUpdateInfo.updateAvailability()}")
            Log.i(TAG, "🔍 Available Version Code: ${appUpdateInfo.availableVersionCode()}")
            Log.i(TAG, "🔍 Is Immediate Update Allowed: ${appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)}")
            Log.i(TAG, "🔍 Is Flexible Update Allowed: ${appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)}")
            Log.i(TAG, "🔍 Install Status: ${appUpdateInfo.installStatus()}")

            // Decode update availability status
            val updateStatus = when (appUpdateInfo.updateAvailability()) {
                UpdateAvailability.UPDATE_AVAILABLE -> {
                    isUpdateAvailable = true
                    "✅ UPDATE_AVAILABLE - An update is available"
                }
                UpdateAvailability.UPDATE_NOT_AVAILABLE -> {
                    isUpdateAvailable = false
                    "ℹ️ UPDATE_NOT_AVAILABLE - No update available"
                }
                UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> {
                    isUpdateAvailable = true
                    "⚠️ DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS - Update already in progress"
                }
                UpdateAvailability.UNKNOWN -> {
                    isUpdateAvailable = false
                    "❓ UNKNOWN - Update status unknown"
                }
                else -> {
                    isUpdateAvailable = false
                    "❓ UNEXPECTED STATUS - ${appUpdateInfo.updateAvailability()}"
                }
            }
            Log.i(TAG, updateStatus)
            Log.i(TAG, "======================================")

            // Check if update is available and IMMEDIATE type is allowed
            if (appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                && appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
            ) {
                // Update available - start IMMEDIATE update flow
                startImmediateUpdate(appUpdateInfo)
            } else if (appUpdateInfo.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                // Update was started but not completed - resume it
                startImmediateUpdate(appUpdateInfo)
            } else {
                // No update available or not required
                Log.i(TAG, "✅ No update required - proceeding with normal app flow")
                isUpdateAvailable = false
                isUpdateDialogShowing = false

                // Only show background location dialog if NO update is showing
                checkAndRequestBackgroundLocation()
            }
        }.addOnFailureListener { e ->
            Log.e(TAG, "❌ Failed to check for updates: ${e.message}")
            e.printStackTrace()

            // ✅ Check if error is ERROR_APP_NOT_OWNED (-10)
            // This happens when app is sideloaded or installed via ADB (not from Play Store)
            val isAppNotOwnedError = e.message?.contains("-10") == true ||
                                     e.message?.contains("ERROR_APP_NOT_OWNED") == true ||
                                     e.message?.contains("not owned by any user") == true

            if (isAppNotOwnedError) {
                Log.w(TAG, "⚠️ App not installed from Play Store - In-App Updates unavailable")
                Log.i(TAG, "ℹ️ This is expected for debug builds or sideloaded apps")
                Log.i(TAG, "ℹ️ In-App Updates only work for apps downloaded from Play Store")
                Log.i(TAG, "ℹ️ NOTE: Install from Internal Testing or Production track to test updates")
            }

            // On update check failure - continue with normal app flow
            isUpdateAvailable = false
            isUpdateDialogShowing = false
            checkAndRequestBackgroundLocation()
        }
    }

    /**
     * Start IMMEDIATE update flow
     * This blocks app usage until update is installed
     */
    private fun startImmediateUpdate(appUpdateInfo: com.google.android.play.core.appupdate.AppUpdateInfo) {
        try {
            Log.i(TAG, "🔄 Starting IMMEDIATE update flow...")
            isUpdateDialogShowing = true

            appUpdateManager.startUpdateFlowForResult(
                appUpdateInfo,
                activityResultLauncher,
                AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
            )
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error starting update flow: ${e.message}", e)
            isUpdateDialogShowing = false
            // Show fallback dialog to manually update via Play Store
            showUpdateRequiredDialog()
        }
    }

    /**
     * Check if background location permission is granted
     * If not, show dialog to request it
     * Only shows if update dialog is NOT showing (prevents overlap)
     */
    private fun checkAndRequestBackgroundLocation() {
        // ✅ Don't show background location dialog if update dialog is showing
        if (isUpdateDialogShowing || isUpdateAvailable) {
            Log.i(TAG, "⏸️ Skipping background location check - update dialog is showing")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val backgroundLocationPermission = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            )

            if (backgroundLocationPermission != PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "⚠️ Background location permission not granted, showing dialog")
                showBackgroundLocationPermissionDialog()
            } else {
                Log.i(TAG, "✅ Background location permission already granted")
            }
        } else {
            Log.i(TAG, "ℹ️ Android version < Q, background location permission not required")
        }
    }

    /**
     * Show dialog to request background location permission
     */
    private fun showBackgroundLocationPermissionDialog() {
        // ✅ Double-check update dialog is not showing before showing this dialog
        if (isUpdateDialogShowing || isUpdateAvailable) {
            Log.i(TAG, "⏸️ Skipping background location dialog - update in progress")
            return
        }

        // ✅ Use DialogManager to queue dialog if one is already showing
        DialogManager.queueDialog(
            id = "background_location_permission",
            type = DialogManager.DialogType.PERMISSION
        ) {
            // Close drawer before showing dialog
            closeDrawerSafely()

            AlertDialog.Builder(this)
                .setTitle("Background Location Required")
                .setMessage("This app needs background location access to track your location even when the app is closed or not in use. This is required for accurate attendance tracking.\n\nPlease grant \"Allow all the time\" in the next screen.")
                .setPositiveButton("Grant Permission") { _, _ ->
                    // Request background location permission
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ActivityCompat.requestPermissions(
                            this,
                            arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                            PERMISSIONS_REQUEST_CODE
                        )
                    }
                }
                .setNegativeButton("Not Now") { dialog, _ ->
                    dialog.dismiss()
                    Log.i(TAG, "⚠️ User declined background location permission")
                }
                .setCancelable(false)
                .create()
        }
    }

    /**
     * Show a non-dismissible dialog informing user that app update is required
     * User cannot use the app without updating
     */
    private fun showUpdateRequiredDialog() {
        AlertDialog.Builder(this)
            .setTitle("Update Required")
            .setMessage("A new version of the app is available. You must update to continue using the app.")
            .setPositiveButton("Update Now") { _, _ ->
                // Try to open Play Store
                openPlayStoreForUpdate()
            }
            .setCancelable(false) // User cannot dismiss this dialog
            .show()
    }

    /**
     * Open Play Store to app's page for manual update
     */
    private fun openPlayStoreForUpdate() {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("market://details?id=$packageName")
                setPackage("com.android.vending")
            }
            startActivity(intent)
        } catch (e: Exception) {
            // If Play Store app is not available, open in browser
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to open Play Store: ${e.message}")
                // Close app if cannot update
                finish()
            }
        }
    }

    private fun startPermissionAction(){
        checkPermissionsAndStartService()
        // ⏸️ SKIP battery dialog during onCreate if app is already initializing
        // It will be checked again in onResume with proper sequencing
        Log.i(TAG, "startPermissionAction: Deferring battery optimization check to onResume")
    }

    private fun setUpProgressDialog(
    ) {
        if (mProgressDialog != null && mProgressDialog!!.isShowing) {
            return
        }
        val inflater = LayoutInflater.from(this)
        progressDialogBinding = LoadingDialogBinding.inflate(inflater)
        mProgressDialog = Dialog(this).apply {
            setContentView(progressDialogBinding.root)
            setCancelable(false)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

            // Set dismiss listener to notify DialogManager
            setOnDismissListener {
                Log.i(TAG, "Progress dialog dismissed")
            }
        }
    }

    fun showProgressDialog(message: String) {
        progressDialogBinding.titleTv.text = message
        if (mProgressDialog != null && mProgressDialog?.isShowing == false) {
            mProgressDialog?.show()
            // ✅ Inform DialogManager that loading dialog is showing
            Log.i(TAG, "📊 Progress dialog shown")
        }
    }

    fun dismissProgressDialog() {
        if (mProgressDialog != null && mProgressDialog?.isShowing == true) {
            mProgressDialog?.dismiss()
            Log.i(TAG, "📊 Progress dialog dismissed")
        }
    }

    private fun setupObserver() {
        mainViewModel.logoutResponse.observe(this) { resource ->
            when (resource) {
                is Resource.Loading -> {
                    DialogManager.setApiCallInProgress(true)
                    showProgressDialog(resource.message)
                }

                is Resource.Success -> {
                    DialogManager.setApiCallInProgress(false)
                    Utils.showSnackBar(getString(R.string.logout_successfully), mBinding.root)
                    dismissProgressDialog()
                    deleteUserDataAndLogout()
                }

                is Resource.Error -> {
                    DialogManager.setApiCallInProgress(false)
                    dismissProgressDialog()
                    Log.i(TAG, "setUpObserver: error:${resource.message}")

                    if (resource.message == getString(R.string.unauthorize)) {
                        deleteUserDataAndLogout()
                    } else {
                        showSnackBarMessage(resource.message)
                    }

                }

                else -> {}
            }
        }
        mainViewModel.userDetailsResponse.observe(this) { resource ->
            when (resource) {
                is Resource.Loading -> {
                    DialogManager.setApiCallInProgress(true)
//                    showProgressDialog(resource.message)
                }

                is Resource.Success -> {
                    DialogManager.setApiCallInProgress(false)
                    Log.i(TAG, "userDetails: successACtivity:${resource.data}")
                    val userModel = resource.data
                    userModel?.let {
//                            SharedPref.getInstance(this)?.saveToken(it.data.accessToken)
                        SharedPref.getInstance(this)?.saveUser(userModel)

                        FirebaseMessaging.getInstance().subscribeToTopic(userModel?._id.toString())
                        val defaultShifts = userModel?.timetable?.range
                        val multiTimeTables =userModel?.multipleTimeTables

                        AlarmScheduler.scheduleAlarms(
                            context = this,
                            defaultShifts = defaultShifts!!,
                            multipleTimeTables = multiTimeTables!!
                        )

                        // ✅ Start 15-minute periodic health check worker
                        ServiceHealthWorkerManager.schedulePeriodicHealthCheck(this)
                        Log.i(TAG, "✅ Periodic health check worker scheduled")
                    }

                }

                is Resource.Error -> {
                    DialogManager.setApiCallInProgress(false)
                    Log.i(TAG, "setUpObserver: error:${resource.message}")
                }

                else -> {}
            }
        }
    }

    private fun showSnackBarMessage(message: String) {
        Snackbar.make(mBinding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    /**
     * Safely close drawer - used before showing dialogs
     * This ensures drawer closes immediately and doesn't interfere with dialogs
     */
    fun closeDrawerSafely() {
        try {
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START, false)
                Log.i(TAG, "Drawer closed safely")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error closing drawer: ${e.message}")
        }
    }

    fun prepareDrawerForLoggedOutState() {
        closeDrawerSafely()
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
    }

    private fun setupDrawerDestinationState() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
            ?: return
        val navController = navHostFragment.navController
        navController.addOnDestinationChangedListener { _, destination, _ ->
            if (destination.id == R.id.loginFragment || destination.id == R.id.splashFragment) {
                prepareDrawerForLoggedOutState()
            } else {
                drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
            }
        }
    }

    private fun deleteUserDataAndLogout() {
        prepareDrawerForLoggedOutState()

        lifecycleScope.launch {
            locationTrack.stopListener()
            val user=SharedPref.getInstance(this@MainActivity)?.getUser()
            user?.let {
                FirebaseMessaging.getInstance().unsubscribeFromTopic(it?._id.toString())
            }

            // Stop all schedulers/workers/alarms
            AlarmScheduler.cancelAllAlarms(this@MainActivity)
            PeriodicSyncWorkerManager.stopPeriodicSync(this@MainActivity)
            // ✅ Cancel periodic health check worker
            ServiceHealthWorkerManager.cancelPeriodicHealthCheck(this@MainActivity)
            ShiftRestartAlarmManager.cancelAllRestartSchedules(this@MainActivity)
            RestartWatchdogManager.cancelOneMinuteRestart(this@MainActivity)
            Log.i(TAG, "✅ Periodic health check worker cancelled on logout")

            // Clear notifications and stop service
            val notificationManager = this@MainActivity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancelAll()

            if (isServiceRunning(this@MainActivity, MyService::class.java)) {
                Log.i(TAG, "Service is running. Stopping it now.")
                this@MainActivity.stopService(Intent(this@MainActivity, MyService::class.java))
            }

            FingerprintHelper.setFingerprintEnabled(this@MainActivity, false)
            SharedPref.getInstance(this@MainActivity)?.clearLastSyncTime() // Clear sync timestamps
            SharedPref.getInstance(this@MainActivity)?.clearPrefrence()
            val navController = findNavController(R.id.nav_host_fragment)
            val navOptions = navOptions {
                popUpTo(R.id.nav_graph) { inclusive = true }
                launchSingleTop = true
            }
            if (navController.currentDestination?.id != R.id.loginFragment) {
                navController.navigate(R.id.loginFragment, null, navOptions)
            }

            prepareDrawerForLoggedOutState()
        }
    }

    private fun showForceLogoutDialog(message: String) {
        if (isFinishing || isDestroyed) return
        if (forceLogoutDialog?.isShowing == true) return

        // Close drawer before showing dialog
        closeDrawerSafely()

        forceLogoutDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.session_expired_title))
            .setMessage(message)
            .setPositiveButton(getString(R.string.ok)) { dialog, _ ->
                dialog.dismiss()
                deleteUserDataAndLogout()
            }
            .setCancelable(false)
            .create()

        forceLogoutDialog?.setCanceledOnTouchOutside(false)
        forceLogoutDialog?.show()
    }

    private fun setupDrawer() {
        // Initialize switch from binding
        val switchFingerprint = mBinding.switchFingerprint

        // Set initial state from SharedPref
        switchFingerprint.isChecked =FingerprintHelper.isFingerprintEnabled(this)

        // Handle switch toggle
        switchFingerprint.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!FingerprintHelper.isFingerprintSupported(this)) {
                    Utils.showSnackBar(getString(R.string.device_doesn_t_support_fingerprint),mBinding.root)

                    switchFingerprint.isChecked = false
                    return@setOnCheckedChangeListener
                }

                if (!FingerprintHelper.isFingerprintAvailable(this)) {
                    // Close drawer before showing dialog
                    closeDrawerSafely()

                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.fingerprint_not_enabled))
                        .setMessage(getString(R.string.please_enable_fingerprint_in_your_device_settings))
                        .setPositiveButton(getString(R.string.go_to_settings)) { _, _ ->
                            FingerprintHelper.openSecuritySettings(this)
                        }
                        .setNegativeButton(getString(R.string.cancel), null)
                        .show()
                    switchFingerprint.isChecked = false
                    return@setOnCheckedChangeListener
                }

                FingerprintHelper.setFingerprintEnabled(this, true)
            } else {
                FingerprintHelper.setFingerprintEnabled(this, false)
            }
        }
        val user = SharedPref.getInstance(this@MainActivity)?.getUser()

        Log.i(TAG, "setupDrawer: userData:${user}")
        
        mBinding.headerLl.userName.text=user?.name?:getString(R.string.app_name)
        mBinding.headerLl.userDesTv.text = "Organization: ${user?.organization?.name.takeIf { !it.isNullOrBlank() } ?: "NA"}"


        // Handle clicks on menu items using binding
        mBinding.navProfile.setOnClickListener {
            navigateToProfile()
            mBinding.drawerLayout.closeDrawer(GravityCompat.START)
        }

        mBinding.navAttendance.setOnClickListener {
            navigateToRecords()
            mBinding.drawerLayout.closeDrawer(GravityCompat.START)
        }

        mBinding.navTimeSheet.setOnClickListener {
            navigateToTimeSheet()
            mBinding.drawerLayout.closeDrawer(GravityCompat.START)
        }

        mBinding.navMyShifts.setOnClickListener {
            navigateToMyShifts()
            mBinding.drawerLayout.closeDrawer(GravityCompat.START)
        }

        mBinding.navLogout.setOnClickListener {
            showLogoutDialog()
            mBinding.drawerLayout.closeDrawer(GravityCompat.START)
        }
    }

    private fun navigateToMyShifts() {
        val navController = findNavController(R.id.nav_host_fragment)
        navController.navigate(R.id.myShiftsFragment)
        drawerLayout.closeDrawer(GravityCompat.START)
    }


    private fun showLogoutDialog() {
        // ✅ Prevent user-initiated logout if forced logout is already showing
        if (isLogoutDialogShowing) {
            Log.w(TAG, "⚠️ Forced logout dialog already showing, skipping user-initiated logout")
            return
        }
        
        if (logoutDialog?.isShowing == true) return  // Prevent duplicate dialog

        // Close drawer before showing dialog
        closeDrawerSafely()

        val dialogBinding = LogoutDialogBinding.inflate(LayoutInflater.from(this@MainActivity)) // Use ViewBinding

        logoutDialog = Dialog(this@MainActivity).apply {
            setContentView(dialogBinding.root) // Set root view from binding
            setCancelable(true)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

            dialogBinding.btnLogoutConfirm.setOnClickListener {
                dismiss()  // Close dialog
                performLogout()  // Call logout function
            }

            dialogBinding.btnCloseDialog.setOnClickListener {
                dismiss()  // Close dialog
            }

            // ✅ Clear logout flag when user-initiated logout dialog is dismissed
            setOnDismissListener {
                Log.i(TAG, "✅ User logout dialog dismissed")
            }

            show()
        }
    }

    private fun performLogout() {
        lifecycleScope.launch {

            val user = SharedPref.getInstance(this@MainActivity)?.getUser()
            val token = SharedPref.getInstance(this@MainActivity)?.getToken()
            user?.let {
                if (Utils.isInternetAvailable(this@MainActivity)) {
                    mainViewModel.logoutUser(user_token = token ?: "", id = it._id ?: "")
                } else {
                    Utils.showSnackBar(
                        getString(R.string.no_network_connection),
                        mBinding.root
                    )
                }
            }
        }

    }

    fun navigateToProfile() {
        val navController = findNavController(R.id.nav_host_fragment)
        navController.navigate(R.id.profileFragment)
        drawerLayout.closeDrawer(GravityCompat.START)
    }

    fun navigateToRecords(){
        val navController = findNavController(R.id.nav_host_fragment)
        navController.navigate(R.id.attendanceFragment)
        drawerLayout.closeDrawer(GravityCompat.START)
    }

    fun navigateToErrors(){
        val navController = findNavController(R.id.nav_host_fragment)
        navController.navigate(R.id.errorsSolutionsFragment)
        drawerLayout.closeDrawer(GravityCompat.START)
    }

    fun navigateToTimeSheet(){
        val navController = findNavController(R.id.nav_host_fragment)
        navController.navigate(R.id.timeSheetFragment)
        drawerLayout.closeDrawer(GravityCompat.START)
    }

    fun toggleDrawer() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            drawerLayout.openDrawer(GravityCompat.START)
        }
    }
    // Step 3: Check if battery optimizations are ignored
    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    // Step 4: Request user to disable battery optimizations
    private fun requestIgnoreBatteryOptimization() {
        // ✅ Skip if any dialog is already showing
        if (isUpdateDialogShowing || isUpdateAvailable || isFullScreenIntentDialogShowing) {
            Log.i(TAG, "⏸️ Skipping battery optimization dialog - another dialog is showing")
            return
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                Log.i(TAG, "🔋 Requesting user to disable battery optimizations.")
                isBatteryOptimizationDialogShowing = true

                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request battery optimization, opening settings manually", e)
                isBatteryOptimizationDialogShowing = true
                openBatteryOptimizationSettings()
            }
        } else {
            Log.i(TAG, "✅ Battery optimization already ignored")
        }
    }

    // Step 5: Handle the result when the user returns from settings
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) {
            // ✅ Clear battery dialog state when returning
            isBatteryOptimizationDialogShowing = false
            if (isIgnoringBatteryOptimizations()) {
                Log.i(TAG, "✅ User successfully disabled battery optimizations.")
            } else {
                Log.w(TAG, "⚠️ User did NOT disable battery optimizations (might retry later).")
            }
            // ✅ Continue with next permission dialog
            Log.i(TAG, "📋 Battery dialog closed, proceeding to next dialog...")
            checkAndRequestFullScreenIntentPermission()
        }
    }

    // Step 6: Open Battery Optimization Settings manually
    private fun openBatteryOptimizationSettings() {
        try {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open battery optimization settings", e)
        }
    }

    // Step 8 (Optional): Restart the app after permission is granted
    private fun restartApp() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        Runtime.getRuntime().exit(0) // Force restart
    }

    override fun onResume() {
        super.onResume()
        Log.i(TAG, "onResume: Activity resumed")

        SessionLogoutCoordinator.consumePendingMessage()?.let { pendingMessage ->
            showForceLogoutDialog(pendingMessage)
        }

        // ✅ Clear battery dialog state when returning from system dialogs
        isBatteryOptimizationDialogShowing = false

        // ✅ Check if update was downloaded while app was in background
        // If update is in IMMEDIATE mode and user returns, they must install it
        appUpdateManager.appUpdateInfo.addOnSuccessListener { appUpdateInfo ->
            if (appUpdateInfo.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                // Update download started but user left - resume the update flow
                Log.i(TAG, "⚠️ Update in progress detected in onResume, resuming...")
                isUpdateDialogShowing = true
                isUpdateAvailable = true
                try {
                    appUpdateManager.startUpdateFlowForResult(
                        appUpdateInfo,
                        activityResultLauncher,
                        AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to resume update: ${e.message}")
                    isUpdateDialogShowing = false
                    showUpdateRequiredDialog()
                }
            } else if (appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                && appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
                // Update available but not started - enforce it
                Log.i(TAG, "⚠️ Update available on resume, enforcing...")
                isUpdateDialogShowing = true
                isUpdateAvailable = true
                try {
                    appUpdateManager.startUpdateFlowForResult(
                        appUpdateInfo,
                        activityResultLauncher,
                        AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to start update: ${e.message}")
                    isUpdateDialogShowing = false
                    showUpdateRequiredDialog()
                }
            } else {
                // No update needed - proceed with sequential permission dialogs
                isUpdateDialogShowing = false
                isUpdateAvailable = false
                showPermissionDialogsSequentially()
            }
        }
    }

    /**
     * Show permission dialogs one at a time to prevent overlapping
     * Sequence: Battery Optimization → Full-Screen Intent
     */
    private fun showPermissionDialogsSequentially() {
        Log.i(TAG, "📋 Starting sequential permission dialog sequence...")
        
        // First, check battery optimization
        if (!isIgnoringBatteryOptimizations()) {
            Log.i(TAG, "Step 1/2: Showing battery optimization dialog")
            requestIgnoreBatteryOptimization()
        } else {
            Log.i(TAG, "✅ Step 1/2: Battery optimization already ignored, proceeding to next...")
            // Battery already ok, move to full-screen intent
            checkAndRequestFullScreenIntentPermission()
        }
    }

    private fun checkAndRequestFullScreenIntentPermission() {
        // ✅ Skip if any dialog is already showing
        if (isUpdateDialogShowing || isUpdateAvailable || isBatteryOptimizationDialogShowing) {
            Log.i(TAG, "⏸️ Skipping full-screen intent check - another dialog is showing")
            return
        }

        if (!FullScreenIntentPermissionHelper.isRuntimePermissionCheckSupported()) {
            Log.i(TAG, "ℹ️ Full-screen intent runtime permission not required on this Android version")
            return
        }

        val canUseFsi = FullScreenIntentPermissionHelper.canUseFullScreenIntent(this)
        Log.i(TAG, "Step 2/2: Checking full-screen intent permission (granted: $canUseFsi)")

        if (!canUseFsi) {
            Log.i(TAG, "⚠️ Step 2/2: Full-screen intent permission missing, showing dialog")
            showFullScreenIntentPermissionDialog()
        } else {
            Log.i(TAG, "✅ Step 2/2: Full-screen intent permission already granted")
        }
    }

    private fun showFullScreenIntentPermissionDialog() {
        if (isFinishing || isDestroyed) return

        // ✅ Mark dialog as showing to prevent overlap
        isFullScreenIntentDialogShowing = true

        // ✅ Use DialogManager to queue dialog if one is already showing
        DialogManager.queueDialog(
            id = "full_screen_intent_permission",
            type = DialogManager.DialogType.FULL_SCREEN_INTENT
        ) {
            // Close drawer before showing dialog
            closeDrawerSafely()

            AlertDialog.Builder(this)
                .setTitle("Enable Full-Screen Alerts")
                .setMessage(
                    "To show compliant alerts instantly over other apps and on lock screen, allow Full-screen alerts for this app in system settings."
                )
                .setPositiveButton("Open Settings") { _, _ ->
                    openFullScreenIntentSettings()
                }
                .setNegativeButton("Not Now") { dialog, _ ->
                    dialog.dismiss()
                }
                .create()
                .apply {
                    // ✅ Clear dialog state when dismissed to allow next dialog to show
                    setOnDismissListener {
                        isFullScreenIntentDialogShowing = false
                        Log.i(TAG, "✅ Full-screen intent dialog dismissed, cleared state")
                    }
                }
        }
    }

    private fun openFullScreenIntentSettings() {
        try {
            val intent = FullScreenIntentPermissionHelper.buildManageFullScreenIntentSettingsIntent(this)
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Full-screen alerts settings directly, opening app details", e)
            try {
                val fallbackIntent = FullScreenIntentPermissionHelper.buildAppDetailsSettingsIntent(this)
                startActivity(fallbackIntent)
            } catch (fallbackError: Exception) {
                Log.e(TAG, "Failed to open app settings fallback", fallbackError)
            }
        }
    }

    fun showForcedLogoutDialog(message: String) {
        // ✅ Prevent overlap: Only show if no other dialogs are showing
        if (isUpdateDialogShowing || isFullScreenIntentDialogShowing || isBatteryOptimizationDialogShowing) {
            Log.w(TAG, "⚠️ Another dialog is showing, deferring forced logout dialog")
            return
        }

        isLogoutDialogShowing = true
        Log.i(TAG, "📋 Showing forced logout dialog")

        val logoutMessage = if (message.equals("LOGOUT", ignoreCase = true)) {
            "You have been forcefully logged out by admin.\nYou can no longer use this app."
        } else {
            message.takeIf { it.isNotBlank() } ?: "Your session is no longer valid.\nPlease login again."
        }

        forceLogoutDialog = AlertDialog.Builder(this)
            .setTitle("Session Ended")
            .setMessage(logoutMessage)
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ ->
                isLogoutDialogShowing = false
                deleteUserDataAndLogout()
            }
            .create()
            .apply {
                setOnDismissListener {
                    isLogoutDialogShowing = false
                    Log.i(TAG, "✅ Forced logout dialog dismissed")
                }
                show()
            }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleComplaintIntent(intent)
    }

    private fun handleComplaintIntent(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_COMPLAINT_CHECK, false)) {
            Log.i(TAG, "✅ Complaint check detected in intent")
            // ✅ On cold start (onCreate), HomeFragment is not yet attached so a broadcast
            //    would be lost. We leave the extra on the intent so HomeFragment reads it
            //    directly in onResume() after navigation from SplashFragment.
            //
            // ✅ On warm start (onNewIntent), HomeFragment IS already attached and listening,
            //    so we send the broadcast to reach it immediately.
            val isHomeVisible = try {
                val navHost = findNavController(R.id.nav_host_fragment)
                navHost.currentDestination?.id == R.id.homeFragment
            } catch (e: Exception) { false }

            if (isHomeVisible) {
                Log.i(TAG, "✅ HomeFragment visible — sending complaint broadcast directly")
                val broadcast = Intent(ACTION_PERFORM_COMPLAINT_CHECK)
                androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this)
                    .sendBroadcast(broadcast)
            } else {
                Log.i(TAG, "⏳ HomeFragment not yet visible — intent extra kept for onResume pickup")
            }
        }
    }

    private fun checkPermissionsAndStartService() {
        Log.i(TAG, "checkPermissionsAndStartService: Checking permissions")

        if (hasPermissions()) {
            // Permissions granted, start the service
            Log.i(TAG, "checkPermissionsAndStartService: Permissions granted, starting service")
            startMyService()
        } else {
            // Request permissions
            Log.i(TAG, "checkPermissionsAndStartService: Permissions not granted, requesting permissions")
            requestPermissions()
        }
    }

    private fun hasPermissions(): Boolean {
        val fineLocationPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        )
        val backgroundLocationPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
        )

        val hasPermissions = fineLocationPermission == PackageManager.PERMISSION_GRANTED
        val backgroundPermissionGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            backgroundLocationPermission == PackageManager.PERMISSION_GRANTED
        } else {
            true  // No need for background location permission on Android versions before 10
        }

        Log.i(TAG, "hasPermissions: Fine Location permission = $fineLocationPermission, Background Location permission = $backgroundLocationPermission, Permissions granted = $hasPermissions and background = $backgroundPermissionGranted")
        return hasPermissions && backgroundPermissionGranted
    }

    private fun requestPermissions() {
        // Request Fine Location and Background Location permissions
        val permissions = mutableListOf<String>()

        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }


        Log.i(TAG, "requestPermissions: Requesting permissions = ${permissions.joinToString()}")
        ActivityCompat.requestPermissions(
            this,
            permissions.toTypedArray(),
            PERMISSIONS_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Log.i(TAG, "onRequestPermissionsResult: requestCode = $requestCode, permissions = ${permissions.joinToString()}, grantResults = ${grantResults.joinToString()}")

        if (requestCode == PERMISSIONS_REQUEST_CODE)
        {
            if (grantResults.isNotEmpty()) {
                val fineLocationGranted = grantResults[0] == PackageManager.PERMISSION_GRANTED

                Log.i(TAG, "onRequestPermissionsResult: Fine Location permission granted = $fineLocationGranted,")

                if (fineLocationGranted ) {
                    // Both permissions granted, start the service
                    Log.i(TAG, "onRequestPermissionsResult: Both permissions granted, starting service")
                    startMyService()
                }  else {
                    // Permissions denied, show a message to the user
                    Log.i(TAG, "onRequestPermissionsResult: Permissions denied")
//                    Toast.makeText(this, "Permissions are required for the app to function properly.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startMyService() {
        Log.i(TAG, "startMyService: Checking if MyService is already running")

        val user = SharedPref.getInstance(this)?.getUser()
        val token = SharedPref.getInstance(this)?.getToken()
        Log.i(TAG, "startMyService: Retrieved user info = $user")

        if (Utils.isInternetAvailable(this))
        {
            user?.let {
                mainViewModel.userDetails(user_token = token ?: "", id = it._id ?: "")
            }
        }else{
            if (user != null && user.isActive) {
                Log.i(TAG, "startMyService: User is active, scheduling alarms with WorkManager")

                // Schedule WorkManager for periodic API calls
                user.timetable?.range?.let {
                    AlarmScheduler.scheduleAlarms(this, it,user.multipleTimeTables!!)
                    Log.i(TAG, "startMyService: Alarms scheduled with range = ${it}")

                    // ✅ Start 15-minute periodic health check worker
                    ServiceHealthWorkerManager.schedulePeriodicHealthCheck(this)
                    Log.i(TAG, "✅ Periodic health check worker scheduled (offline mode)")
                }
            }
        }


    }

    private fun registerForceLogoutReceiver() {
        if (isForceLogoutReceiverRegistered) return
        ContextCompat.registerReceiver(
            this,
            forceLogoutReceiver,
            IntentFilter(SessionLogoutCoordinator.ACTION_FORCE_LOGOUT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        isForceLogoutReceiverRegistered = true
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isForceLogoutReceiverRegistered) {
            unregisterReceiver(forceLogoutReceiver)
            isForceLogoutReceiverRegistered = false
        }
    }


}
