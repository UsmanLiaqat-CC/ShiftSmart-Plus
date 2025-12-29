package com.shiftsmart.plus.ui.activities

import android.Manifest
import android.app.AlarmManager
import android.app.Dialog
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
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
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.messaging.FirebaseMessaging
import com.shiftsmart.plus.R
import com.shiftsmart.plus.databinding.ActivityMainBinding
import com.shiftsmart.plus.databinding.LoadingDialogBinding
import com.shiftsmart.plus.databinding.LogoutDialogBinding
import com.shiftsmart.plus.periodicAction.AlarmScheduler
import com.shiftsmart.plus.periodicAction.ServiceHealthWorkerManager
import com.shiftsmart.plus.services.LocationTrack
import com.shiftsmart.plus.services.MyService
import com.shiftsmart.plus.utils.FingerprintHelper
import com.shiftsmart.plus.utils.Resource
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
    private lateinit var activityResultLauncher: ActivityResultLauncher<IntentSenderRequest>
    private lateinit var appUpdateManager: com.google.android.play.core.appupdate.AppUpdateManager

    private val PERMISSIONS_REQUEST_CODE = 100
    private  val TAG = "MainActivityPLUS"
    private  val REQUEST_IGNORE_BATTERY_OPTIMIZATIONS = 1001

    lateinit var drawerLayout: DrawerLayout
    private lateinit var mBinding:ActivityMainBinding
    private var logoutDialog: Dialog? = null  // Keep reference to avoid multiple dialogs

    @Inject
    lateinit var locationTrack: LocationTrack
    private var mProgressDialog: Dialog? = null
    private lateinit var progressDialogBinding: LoadingDialogBinding


    val mainViewModel: MainViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize app update manager
        appUpdateManager = AppUpdateManagerFactory.create(this)

        activityResultLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            // Handle the result of the update flow here
            if (result.resultCode != RESULT_OK) {
                Log.d(TAG, "❌ Update flow failed! Result code: ${result.resultCode}")
                // User cancelled or update failed - show non-dismissible dialog
                showUpdateRequiredDialog()
            } else {
                Log.d(TAG, "✅ Update flow completed successfully")
            }
        }

        // Check for app updates using Google Play Core library
        checkForAppUpdates()

        mBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(mBinding.root)

        drawerLayout = mBinding.drawerLayout
        Log.i(TAG, "onCreate: Activity created")

        startPermissionAction()
        setupDrawer()
        setUpProgressDialog()

        setupObserver()

    }


    private fun checkForAppUpdates() {
        val appUpdateManager = AppUpdateManagerFactory.create(this)
        val appUpdateInfoTask = appUpdateManager.appUpdateInfo

        appUpdateInfoTask.addOnSuccessListener { appUpdateInfo ->
            // ✅ ENHANCED LOGGING - Debug why update isn't showing
            Log.i(TAG, "========== APP UPDATE CHECK ==========")
            Log.i(TAG, "📱 Current Version Code: ${packageManager.getPackageInfo(packageName, 0).versionCode}")
            Log.i(TAG, "📱 Current Version Name: ${packageManager.getPackageInfo(packageName, 0).versionName}")
            Log.i(TAG, "🔍 Update Availability: ${appUpdateInfo.updateAvailability()}")
            Log.i(TAG, "🔍 Available Version Code: ${appUpdateInfo.availableVersionCode()}")
            Log.i(TAG, "🔍 Is Immediate Update Allowed: ${appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)}")
            Log.i(TAG, "🔍 Is Flexible Update Allowed: ${appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)}")
            Log.i(TAG, "🔍 Install Status: ${appUpdateInfo.installStatus()}")

            // Decode update availability status
            when (appUpdateInfo.updateAvailability()) {
                UpdateAvailability.UPDATE_AVAILABLE -> {
                    Log.i(TAG, "✅ UPDATE_AVAILABLE - An update is available")
                }
                UpdateAvailability.UPDATE_NOT_AVAILABLE -> {
                    Log.i(TAG, "ℹ️ UPDATE_NOT_AVAILABLE - No update available")
                }
                UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> {
                    Log.i(TAG, "⚠️ DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS - Update already in progress")
                }
                UpdateAvailability.UNKNOWN -> {
                    Log.i(TAG, "❓ UNKNOWN - Update status unknown")
                }
            }
            Log.i(TAG, "======================================")

            if (appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                && appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
            ) {
                // Request the update - IMMEDIATE type blocks app usage until updated
                try {
                    Log.i(TAG, "🔄 Update available! Starting immediate update flow...")
                    appUpdateManager.startUpdateFlowForResult(
                        appUpdateInfo,
                        activityResultLauncher,
                        AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error starting update flow: ${e.message}")
                    // Show dialog that app cannot be used without update
                    showUpdateRequiredDialog()
                }
            } else {
                Log.i(TAG, "✅ No update available or update not required")
                Log.i(TAG, "ℹ️ NOTE: Internal App Sharing does NOT work with In-App Update API!")
                Log.i(TAG, "ℹ️ Use Internal Testing, Closed Testing, or Open Testing track instead")

                // ✅ No update available - Show background location permission dialog
                checkAndRequestBackgroundLocation()
            }
        }.addOnFailureListener { e ->
            Log.e(TAG, "❌ Failed to check for updates: ${e.message}")
            e.printStackTrace()

            // ✅ On update check failure - also show background location dialog
            checkAndRequestBackgroundLocation()
        }
    }

    /**
     * Check if background location permission is granted
     * If not, show dialog to request it
     */
    private fun checkAndRequestBackgroundLocation() {
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
            .show()
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
        // Step 2: Check if battery optimization needs to be ignored
        if (!isIgnoringBatteryOptimizations()) {
            Log.i(TAG, "onCreate: Requesting to ignore battery optimizations")
            requestIgnoreBatteryOptimization()
        } else {
            Log.i(TAG, "onCreate: Battery optimization already ignored")
            // 🔹 Check Exact Alarm Permission (Android 12+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                if (!alarmManager.canScheduleExactAlarms()) {
                    Log.i(TAG, "❌ Exact Alarm permission is not granted.")
                    // We **cannot** request this permission dynamically, so we prompt the user to enable it
                    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = Uri.parse("package:${packageName}")
                    }
                    startActivity(intent)
                }
            }
        }
    }

    private fun setUpProgressDialog(
    ) {
        if (mProgressDialog != null && mProgressDialog!!.isShowing) {
            return
        }
        val inflater = LayoutInflater.from(this)
        progressDialogBinding = LoadingDialogBinding.inflate(inflater)
        mProgressDialog = Dialog(this)
        mProgressDialog?.setContentView(progressDialogBinding.root)
        mProgressDialog?.setCancelable(false)
        mProgressDialog?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

    }

    fun showProgressDialog(message: String) {
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

    private fun setupObserver() {
        mainViewModel.logoutResponse.observe(this) { resource ->
            when (resource) {
                is Resource.Loading -> {
                    showProgressDialog(resource.message)
                }

                is Resource.Success -> {
                    Utils.showSnackBar(getString(R.string.logout_successfully), mBinding.root)
                    dismissProgressDialog()
                    deleteUserDataAndLogout()
                }

                is Resource.Error -> {
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
//                    showProgressDialog(resource.message)
                }

                is Resource.Success -> {
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
                    Log.i(TAG, "setUpObserver: error:${resource.message}")
                }

                else -> {}
            }
        }
    }

    private fun showSnackBarMessage(message: String) {
        Snackbar.make(mBinding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    private fun deleteUserDataAndLogout() {

        lifecycleScope.launch {
            locationTrack.stopListener()
            if (isServiceRunning(this@MainActivity, MyService::class.java)) {
                // Clear Notifications
                val notificationManager =this@MainActivity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancelAll()
                Log.i("Service", "Service is running. Stopping it now.")

                val stopIntent = Intent(this@MainActivity, MyService::class.java).apply {
                    action = MyService.ACTION_STOP
                }
                this@MainActivity.startService(stopIntent)
            }
            val user=SharedPref.getInstance(this@MainActivity)?.getUser()
            user?.let {
                FirebaseMessaging.getInstance().unsubscribeFromTopic(it?._id.toString())
            }

            // ✅ Cancel periodic health check worker
            ServiceHealthWorkerManager.cancelPeriodicHealthCheck(this@MainActivity)
            Log.i(TAG, "✅ Periodic health check worker cancelled on logout")

            FingerprintHelper.setFingerprintEnabled(this@MainActivity, false)
            SharedPref.getInstance(this@MainActivity)?.clearLastSyncTime() // Clear sync timestamps
            SharedPref.getInstance(this@MainActivity)?.clearPrefrence()
            val navController = findNavController(R.id.nav_host_fragment)
            navController.navigate(R.id.loginFragment)
        }
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
        if (logoutDialog?.isShowing == true) return  // Prevent duplicate dialog

        val drawerLayout = (this@MainActivity)?.drawerLayout
        drawerLayout?.let {
            if (it.isDrawerOpen(GravityCompat.START)) {
                it.closeDrawer(GravityCompat.START)
            }
        }

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
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                Log.i(TAG, "Requesting user to disable battery optimizations.")

                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:$packageName")

//                startActivityForResult(intent, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request battery optimization, opening settings manually", e)
                openBatteryOptimizationSettings()
            }
        }
    }

    // Step 5: Handle the result when the user returns from settings
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) {
            if (isIgnoringBatteryOptimizations()) {
                Log.i(TAG, "User ignored battery optimizations successfully.")
                restartApp() // Optional: Restart the app for changes to take effect
            } else {
                Log.w(TAG, "User did NOT ignore battery optimizations.")
            }
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

        // Check if update was downloaded while app was in background
        // If update is in IMMEDIATE mode and user returns, they must install it
        appUpdateManager.appUpdateInfo.addOnSuccessListener { appUpdateInfo ->
            if (appUpdateInfo.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                // Update download started but user left - resume the update flow
                Log.i(TAG, "⚠️ Update in progress detected, resuming...")
                try {
                    appUpdateManager.startUpdateFlowForResult(
                        appUpdateInfo,
                        activityResultLauncher,
                        AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to resume update: ${e.message}")
                    showUpdateRequiredDialog()
                }
            } else if (appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                && appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
                // Update available but not started - enforce it
                Log.i(TAG, "⚠️ Update available on resume, enforcing...")
                try {
                    appUpdateManager.startUpdateFlowForResult(
                        appUpdateInfo,
                        activityResultLauncher,
                        AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to start update: ${e.message}")
                    showUpdateRequiredDialog()
                }
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
            if (user != null && user.isActive == true) {
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



}
