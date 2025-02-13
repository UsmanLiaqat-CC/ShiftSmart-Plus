package com.shiftsmart.plus.ui.activities

import android.Manifest
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
import androidx.navigation.fragment.findNavController
import com.shiftsmart.plus.R
import com.shiftsmart.plus.periodicAction.AlarmScheduler
import com.shiftsmart.plus.utils.SharedPref
import com.shiftsmart.plus.utils.Utils
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val PERMISSIONS_REQUEST_CODE = 100
    private  val TAG = "MainActivityPLUS"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Log.i(TAG, "onCreate: Activity created")

        // Check if the app has the necessary location permissions
        checkPermissionsAndStartService()

        // Check if battery optimization needs to be ignored
        if (!Utils.isIgnoringBatteryOptimizations(this)) {
            Log.i(TAG, "onCreate: Requesting to ignore battery optimizations")
            requestIgnoreBatteryOptimization(this)
        } else {
            Log.i(TAG, "onCreate: Battery optimization already ignored")
        }
    }

    override fun onResume() {
        super.onResume()
        Log.i(TAG, "onResume: Activity resumed")
    }

    // Request the user to ignore battery optimizations
    fun requestIgnoreBatteryOptimization(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                Log.i(TAG, "requestIgnoreBatteryOptimization: Not ignoring battery optimizations, requesting user permission.")
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:${context.packageName}")
                context.startActivity(intent)
            } else {
                Log.i(TAG, "requestIgnoreBatteryOptimization: Already ignoring battery optimizations.")
            }
        } else {
            Log.i(TAG, "requestIgnoreBatteryOptimization: Battery optimization request not needed (SDK < M).")
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
                val backgroundLocationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    grantResults.getOrNull(1) == PackageManager.PERMISSION_GRANTED
                } else {
                    true
                }

                Log.i(TAG, "onRequestPermissionsResult: Fine Location permission granted = $fineLocationGranted, Background Location permission granted = $backgroundLocationGranted")

                if (fineLocationGranted && backgroundLocationGranted) {
                    // Both permissions granted, start the service
                    Log.i(TAG, "onRequestPermissionsResult: Both permissions granted, starting service")
                    startMyService()
                } else if (fineLocationGranted && !backgroundLocationGranted) {
                    // Fine Location granted, but Background Location not granted
                    Log.i(TAG, "onRequestPermissionsResult: Background Location permission not granted")
                    Toast.makeText(this, "Background location permission is required for full functionality.", Toast.LENGTH_SHORT).show()
                    // Optionally guide user to settings
                    openAppSettings()
                } else {
                    // Permissions denied, show a message to the user
                    Log.i(TAG, "onRequestPermissionsResult: Permissions denied")
                    Toast.makeText(this, "Permissions are required for the app to function properly.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        val uri = Uri.fromParts("package", packageName, null)
        intent.data = uri
        startActivity(intent)
    }



    private fun startMyService() {
        Log.i(TAG, "startMyService: Starting the service or WorkManager task")

        val user = SharedPref.getInstance(this)?.getUser()
        Log.i(TAG, "startMyService: Retrieved user info = $user")

        if (user != null && user.isActive == true) {
            Log.i(TAG, "startMyService: User is active, scheduling alarms with WorkManager")

            // Schedule WorkManager for periodic API calls
            user.timetable?.range?.let {
                AlarmScheduler.scheduleAlarms(this, it)
                Log.i(TAG, "startMyService: Alarms scheduled with range = ${it}")
            }
        } else {
            Log.i(TAG, "startMyService: User is inactive or not found")
        }
    }
}
