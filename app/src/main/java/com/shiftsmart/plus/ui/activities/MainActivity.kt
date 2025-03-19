package com.shiftsmart.plus.ui.activities

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.fragment.findNavController
import com.shiftsmart.plus.R
import com.shiftsmart.plus.periodicAction.AlarmScheduler
import com.shiftsmart.plus.services.MyService
import com.shiftsmart.plus.utils.SharedPref
import com.shiftsmart.plus.utils.Utils
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val PERMISSIONS_REQUEST_CODE = 100
    private  val TAG = "MainActivityPLUS"
    private  val REQUEST_IGNORE_BATTERY_OPTIMIZATIONS = 1001


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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
        // Call this function when the user needs to enable Auto-Start
//        openAutoStartSettings(this)

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
//                showManualBatteryOptimizationDialog()
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

    // Step 7: Show a dialog if the user did not grant permission
    private fun showManualBatteryOptimizationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Disable Battery Optimization")
            .setMessage("For the app to work properly, go to settings and select 'Don't restrict'.")
            .setPositiveButton("Open Settings") { _, _ -> openBatteryOptimizationSettings() }
            .setNegativeButton("Cancel", null)
            .show()
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
//                val backgroundLocationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
//                    grantResults.getOrNull(1) == PackageManager.PERMISSION_GRANTED
//                } else {
//                    true
//                }

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
    fun openAutoStartSettings(context: Context) {
        val intent = Intent()
        try {
            val manufacturer = Build.MANUFACTURER.lowercase(Locale.ROOT)
            when {
                manufacturer.contains("xiaomi") -> {
                    intent.component = ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"
                    )
                }
                manufacturer.contains("oppo") -> {
                    intent.component = ComponentName(
                        "com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                    )
                }
                manufacturer.contains("vivo") -> {
                    intent.component = ComponentName(
                        "com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                    )
                }
                manufacturer.contains("huawei") -> {
                    intent.component = ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                    )
                }
                else -> {
                    // Open generic settings if manufacturer not recognized
                    intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                    intent.data = Uri.parse("package:${context.packageName}")
                }
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("AutoStart", "Failed to open auto-start settings", e)
            Toast.makeText(context, "Auto-Start settings not available on this device", Toast.LENGTH_SHORT).show()
        }
    }


}
