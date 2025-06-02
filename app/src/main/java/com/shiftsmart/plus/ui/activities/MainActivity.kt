package com.shiftsmart.plus.ui.activities

import android.Manifest
import android.app.AlarmManager
import android.app.Dialog
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
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.findNavController
import androidx.navigation.fragment.findNavController
import com.shiftsmart.plus.R
import com.shiftsmart.plus.databinding.ActivityMainBinding
import com.shiftsmart.plus.databinding.LogoutDialogBinding
import com.shiftsmart.plus.periodicAction.AlarmScheduler
import com.shiftsmart.plus.services.MyService
import com.shiftsmart.plus.utils.FingerprintHelper
import com.shiftsmart.plus.utils.SharedPref
import com.shiftsmart.plus.utils.Utils
import com.shiftsmart.plus.viewmodels.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.Locale

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val PERMISSIONS_REQUEST_CODE = 100
    private  val TAG = "MainActivityPLUS"
    private  val REQUEST_IGNORE_BATTERY_OPTIMIZATIONS = 1001

    lateinit var drawerLayout: DrawerLayout
    private lateinit var mBinding:ActivityMainBinding
    private var logoutDialog: Dialog? = null  // Keep reference to avoid multiple dialogs

    val mainViewModel: MainViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(mBinding.root)

        drawerLayout = mBinding.drawerLayout
        Log.i(TAG, "onCreate: Activity created")

        // Check if the app has the necessary location permissions
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

        setupDrawer()

    }

    private fun setupDrawer() {
        // Initialize switch from binding
        val switchFingerprint = mBinding.switchFingerprint

        // Set initial state from SharedPref
        switchFingerprint.isChecked =FingerprintHelper.isFingerprintEnabled(this) ?: false

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

        mBinding.headerLl.userName.text=user?.name?:getString(R.string.app_name)
        mBinding.headerLl.userDesTv.text="Organization:"+user?.organization?.name


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

        mBinding.navLogout.setOnClickListener {
            showLogoutDialog()
            mBinding.drawerLayout.closeDrawer(GravityCompat.START)
        }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            return pm.isIgnoringBatteryOptimizations(packageName)
        }
        return true // If SDK < M, assume battery optimizations are not applied
    }

    // Step 4: Request user to disable battery optimizations
    private fun requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    Log.i(TAG, "Requesting user to disable battery optimizations.")

                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    intent.data = Uri.parse("package:$packageName")

                    startActivityForResult(intent, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to request battery optimization, opening settings manually", e)
                    openBatteryOptimizationSettings()
                }
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
        Log.i(TAG, "startMyService: Retrieved user info = $user")

        if (user != null && user.isActive == true) {
            Log.i(TAG, "startMyService: User is active, scheduling alarms with WorkManager")

            // Schedule WorkManager for periodic API calls
            user.timetable?.range?.let {
                AlarmScheduler.scheduleAlarms(this, it)
                Log.i(TAG, "startMyService: Alarms scheduled with range = ${it}")
            }
        }
    }



}
