package com.shiftsmart.plus.utils

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class PermissionHandler(
    private val context: Context,
    private val activity: Activity,
    private val onAllPermissionsGranted: () -> Unit
)
{
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private var isForegroundServicePermissionRequested = false

    // Required Permissions List
    private val requiredPermissions = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }

    fun registerPermissionLauncher(launcher: ActivityResultLauncher<Array<String>>) {
        this.permissionLauncher = launcher
    }

    // Step 1: Start Permission Check
    fun checkPermissions() {
        checkPostNotificationPermission()
    }

    // Step 2: Check & Request POST_NOTIFICATIONS
    private fun checkPostNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        } else {
            checkBatteryOptimization()
        }
    }

    // Step 3: Check & Handle Battery Optimization
    private fun checkBatteryOptimization() {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
            checkBatterySaverMode()
        } else {
            showBatteryOptimizationDialog()
        }
    }

    private fun showBatteryOptimizationDialog() {
        AlertDialog.Builder(context)
            .setTitle("Disable Battery Optimization")
            .setMessage("To ensure smooth operation, disable battery optimization for this app.")
            .setPositiveButton("Go to Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                activity.startActivity(intent)
            }
            .setNegativeButton("Cancel") { _, _ -> checkBatterySaverMode() }
            .show()
    }

    // Step 4: Check Battery Saver Mode
    private fun checkBatterySaverMode() {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isPowerSaveMode) {
            requestPermissions()
        } else {
            showBatterySaverDialog()
        }
    }

    private fun showBatterySaverDialog() {
        AlertDialog.Builder(context)
            .setTitle("Battery Saver Enabled")
            .setMessage("Battery Saver mode is enabled. For better performance, disable it.")
            .setPositiveButton("Go to Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                activity.startActivity(intent)
            }
            .setNegativeButton("Cancel") { _, _ -> requestPermissions() }
            .show()
    }

    // Step 5: Request Required Permissions
    private fun requestPermissions() {
        val deniedPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

        if (deniedPermissions.isNotEmpty()) {
            val showRationale = deniedPermissions.any {
                ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
            }

            if (showRationale) {
                // Only show rationale if necessary, otherwise request permissions
                showPermissionRationaleDialog(deniedPermissions)
            } else {
                permissionLauncher.launch(deniedPermissions.toTypedArray())
            }
        } else {
            checkForegroundServicePermission() // If all permissions are granted, continue
        }
    }

    private fun showPermissionRationaleDialog(deniedPermissions: List<String>) {
        if (hasShownRationale) {
            // If already shown once, do not show again — direct to settings or fail silently
            showSettingsDialog(deniedPermissions.first())
            return
        }

        hasShownRationale = true // Set flag

        AlertDialog.Builder(context)
            .setTitle("Permissions Required")
            .setMessage("This app needs background location permissions to work correctly. Please allow them all the time.")
            .setPositiveButton("Grant") { _, _ ->
                permissionLauncher.launch(deniedPermissions.toTypedArray())
            }
            .setNegativeButton("Cancel") { _, _ -> }
            .show()
    }



    // Step 6: Handle Foreground Service Permission Separately
    private fun checkForegroundServicePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.FOREGROUND_SERVICE) != PackageManager.PERMISSION_GRANTED
        ) {
            if (!isForegroundServicePermissionRequested) {
                isForegroundServicePermissionRequested = true
                permissionLauncher.launch(arrayOf(Manifest.permission.FOREGROUND_SERVICE))
            }
        } else {
            onPermissionsGranted()
        }
    }

    private fun showSettingsDialog(missingPermission: String) {
        val intent = when (missingPermission) {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION -> {
                Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            }
            Manifest.permission.POST_NOTIFICATIONS -> {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
            }
            Manifest.permission.FOREGROUND_SERVICE -> {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            }
//            Settings.ACTION_BATTERY_SAVER_SETTINGS -> {
//                Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
//            }
//            Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS -> {
//                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
//            }
            else -> {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            }
        }

        AlertDialog.Builder(context)
            .setTitle("Permissions Required")
            .setMessage("Some permissions were permanently denied or require manual enabling. Please enable them in settings.")
            .setPositiveButton("Go to Settings") { _, _ ->
                activity.startActivity(intent)
            }
            .setNegativeButton("Exit") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
    private var hasShownRationale = false

    fun handlePermissionsResult(grantResults: Map<String, Boolean>) {
        val deniedPermissions = grantResults.filterValues { !it }.keys.toList()

        if (deniedPermissions.isEmpty()) {
            hasShownRationale = false // reset flag if granted
            onPermissionsGranted()
        } else {
            val permanentlyDenied = deniedPermissions.firstOrNull {
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
            }

            if (permanentlyDenied != null) {
                showSettingsDialog(permanentlyDenied)
            } else {
                showPermissionRationaleDialog(deniedPermissions)
            }
        }
    }


    private fun onPermissionsGranted() {
        onAllPermissionsGranted.invoke()
    }

}
