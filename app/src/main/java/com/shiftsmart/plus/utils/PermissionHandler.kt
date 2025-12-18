package com.shiftsmart.plus.utils

import android.Manifest
import android.app.AlertDialog
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
import androidx.fragment.app.Fragment

class PermissionHandler(
    private val fragment: Fragment,
    private val onPermissionsGranted: () -> Unit,
    private val onPermissionsDenied: ((List<String>) -> Unit)? = null
) {
    private val TAG = "PermissionHandler"

    private var permissionLauncher: ActivityResultLauncher<Array<String>>? = null
    private var isForegroundServicePermissionRequested = false
    private var hasShownRationale = false

    /**
     * Initialize the permission launcher. Call this in onCreate or onViewCreated
     */
    fun initializePermissionLauncher(launcher: ActivityResultLauncher<Array<String>>) {
        permissionLauncher = launcher
    }

    /**
     * Check if all required permissions are granted
     */
    fun hasAllPermissions(): Boolean {
        val requiredPermissions = getRequiredPermissions()
        return requiredPermissions.all { permission ->
            ContextCompat.checkSelfPermission(fragment.requireContext(), permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Check if basic login permissions are granted
     */
    fun hasBasicLoginPermissions(): Boolean {
        val basicPermissions = getBasicPermissions()
        return basicPermissions.all { permission ->
            ContextCompat.checkSelfPermission(fragment.requireContext(), permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Check if location permissions are granted
     */
    fun hasLocationPermissions(): Boolean {
        val fineLocation = ContextCompat.checkSelfPermission(
            fragment.requireContext(),
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseLocation = ContextCompat.checkSelfPermission(
            fragment.requireContext(),
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return fineLocation && coarseLocation
    }

    /**
     * Check if background location permission is granted (Android 10+)
     */
    fun hasBackgroundLocationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                fragment.requireContext(),
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Not required on older versions
        }
    }

    /**
     * Check if advanced permissions are needed (background location, battery settings, etc.)
     */
    fun needsAdvancedPermissions(): Boolean {
        return !hasBackgroundLocationPermission() ||
               !isBatteryOptimizationDisabled() ||
               isBatterySaverEnabled()
    }

    /**
     * Check if battery optimization is disabled
     */
    fun isBatteryOptimizationDisabled(): Boolean {
        val powerManager = fragment.requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(fragment.requireContext().packageName)
    }

    /**
     * Check if battery saver mode is enabled
     */
    fun isBatterySaverEnabled(): Boolean {
        val powerManager = fragment.requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isPowerSaveMode
    }

    /**
     * Get list of required permissions based on Android version
     */
    private fun getRequiredPermissions(): List<String> {
        val permissions = mutableListOf<String>()

        // Location permissions
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)

        // Background location for Android 10+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }

        // Notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        return permissions
    }

    /**
     * Get list of permissions that are not yet granted
     */
    private fun getMissingPermissions(): List<String> {
        return getRequiredPermissions().filter { permission ->
            ContextCompat.checkSelfPermission(fragment.requireContext(), permission) != PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Request only basic permissions for login screen
     * (POST_NOTIFICATIONS, ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION)
     * @return true if all basic permissions are already granted, false if requesting
     */
    fun requestLoginPermissions(): Boolean {
        val basicPermissions = getBasicPermissions()
        val missingPermissions = basicPermissions.filter {
            ContextCompat.checkSelfPermission(fragment.requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            Log.i(TAG, "✅ All basic login permissions already granted.")
            onPermissionsGranted()
            return true
        }

        Log.i(TAG, "⏳ Requesting basic login permissions: $missingPermissions")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher?.launch(missingPermissions.toTypedArray())
        } else {
            fragment.requestPermissions(missingPermissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        }

        return false
    }

    /**
     * Get basic permissions needed for login screen only
     */
    private fun getBasicPermissions(): List<String> {
        val permissions = mutableListOf<String>()

        // Basic location permissions (NOT background)
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)

        // Notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        return permissions
    }

    /**
     * Request ALL permissions with comprehensive flow (for Home screen)
     * Step 1: Start Permission Check
     * @return true if all permissions are already granted, false if requesting
     */
    fun requestPermissions(): Boolean {
        val missingPermissions = getMissingPermissions()

        if (missingPermissions.isEmpty()) {
            Log.i(TAG, "✅ All permissions already granted.")
            onPermissionsGranted()
            return true
        }

        Log.i(TAG, "⏳ Starting comprehensive permission flow")
        checkPostNotificationPermission()
        return false
    }

    // Step 2: Check & Request POST_NOTIFICATIONS
    private fun checkPostNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(fragment.requireContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "⏳ Requesting POST_NOTIFICATIONS permission")
            permissionLauncher?.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        } else {
            checkBatteryOptimization()
        }
    }

    // Step 3: Check & Handle Battery Optimization
    private fun checkBatteryOptimization() {
        val powerManager = fragment.requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(fragment.requireContext().packageName)) {
            Log.i(TAG, "✅ Battery optimization already disabled")
            checkBatterySaverMode()
        } else {
            Log.i(TAG, "⚠️ Battery optimization enabled, showing dialog")
            showBatteryOptimizationDialog()
        }
    }

    private fun showBatteryOptimizationDialog() {
        AlertDialog.Builder(fragment.requireContext())
            .setTitle("Disable Battery Optimization")
            .setMessage("To ensure smooth operation, disable battery optimization for this app.")
            .setPositiveButton("Go to Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                fragment.startActivity(intent)
                // Continue flow even if user goes to settings
                checkBatterySaverMode()
            }
            .setNegativeButton("Cancel") { _, _ ->
                checkBatterySaverMode()
            }
            .setCancelable(false)
            .show()
    }

    // Step 4: Check Battery Saver Mode
    private fun checkBatterySaverMode() {
        val powerManager = fragment.requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isPowerSaveMode) {
            Log.i(TAG, "✅ Battery Saver mode is OFF")
            requestLocationPermissions()
        } else {
            Log.i(TAG, "⚠️ Battery Saver mode is ON, showing dialog")
            showBatterySaverDialog()
        }
    }

    private fun showBatterySaverDialog() {
        AlertDialog.Builder(fragment.requireContext())
            .setTitle("Battery Saver Enabled")
            .setMessage("Battery Saver mode is enabled. For better performance, disable it.")
            .setPositiveButton("Go to Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                fragment.startActivity(intent)
                // Continue flow even if user goes to settings
                requestLocationPermissions()
            }
            .setNegativeButton("Cancel") { _, _ ->
                requestLocationPermissions()
            }
            .setCancelable(false)
            .show()
    }

    // Step 5: Request Required Permissions (Location, etc.)
    private fun requestLocationPermissions() {
        val deniedPermissions = getRequiredPermissions().filter {
            // Skip POST_NOTIFICATIONS as it's already handled
            val isPostNotification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                it == Manifest.permission.POST_NOTIFICATIONS
            } else {
                false
            }
            !isPostNotification && ContextCompat.checkSelfPermission(fragment.requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }

        if (deniedPermissions.isNotEmpty()) {
            val showRationale = deniedPermissions.any {
                ActivityCompat.shouldShowRequestPermissionRationale(fragment.requireActivity(), it)
            }

            if (showRationale) {
                showPermissionRationaleDialog(deniedPermissions)
            } else {
                Log.i(TAG, "⏳ Requesting location permissions: $deniedPermissions")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionLauncher?.launch(deniedPermissions.toTypedArray())
                } else {
                    fragment.requestPermissions(deniedPermissions.toTypedArray(), PERMISSION_REQUEST_CODE)
                }
            }
        } else {
            checkForegroundServicePermission()
        }
    }

    private fun showPermissionRationaleDialog(deniedPermissions: List<String>) {
        if (hasShownRationale) {
            // If already shown once, direct to settings
            showSettingsDialog(deniedPermissions.first())
            return
        }

        hasShownRationale = true

        AlertDialog.Builder(fragment.requireContext())
            .setTitle("Permissions Required")
            .setMessage("This app needs background location permissions to work correctly. Please allow them all the time.")
            .setPositiveButton("Grant") { _, _ ->
                Log.i(TAG, "⏳ User agreed to grant permissions: $deniedPermissions")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionLauncher?.launch(deniedPermissions.toTypedArray())
                } else {
                    fragment.requestPermissions(deniedPermissions.toTypedArray(), PERMISSION_REQUEST_CODE)
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                Log.i(TAG, "❌ User cancelled permission rationale")
                onPermissionsDenied?.invoke(deniedPermissions)
            }
            .setCancelable(false)
            .show()
    }

    // Step 6: Handle Foreground Service Permission (Android 14+)
    private fun checkForegroundServicePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            ContextCompat.checkSelfPermission(fragment.requireContext(), Manifest.permission.FOREGROUND_SERVICE) != PackageManager.PERMISSION_GRANTED
        ) {
            if (!isForegroundServicePermissionRequested) {
                isForegroundServicePermissionRequested = true
                Log.i(TAG, "⏳ Requesting FOREGROUND_SERVICE permission")
                permissionLauncher?.launch(arrayOf(Manifest.permission.FOREGROUND_SERVICE))
            }
        } else {
            onAllPermissionsGranted()
        }
    }

    private fun onAllPermissionsGranted() {
        Log.i(TAG, "✅ All permissions granted! Proceeding...")
        hasShownRationale = false // Reset flag
        onPermissionsGranted()
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
                    putExtra(Settings.EXTRA_APP_PACKAGE, fragment.requireContext().packageName)
                }
            }
            Manifest.permission.FOREGROUND_SERVICE -> {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${fragment.requireContext().packageName}")
                }
            }
            else -> {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${fragment.requireContext().packageName}")
                }
            }
        }

        AlertDialog.Builder(fragment.requireContext())
            .setTitle("Permissions Required")
            .setMessage("Some permissions were permanently denied or require manual enabling. Please enable them in settings.")
            .setPositiveButton("Go to Settings") { _, _ ->
                fragment.startActivity(intent)
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                onPermissionsDenied?.invoke(listOf(missingPermission))
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Handle permission result from ActivityResultLauncher
     */
    fun handlePermissionResult(result: Map<String, Boolean>) {
        val deniedPermissions = result.filter { !it.value }.keys.toList()

        if (deniedPermissions.isEmpty()) {
            Log.i(TAG, "✅ All requested permissions granted via launcher")
            hasShownRationale = false // Reset flag

            // Check if this was a basic login permission request or comprehensive flow
            val isBasicRequest = result.keys.none { permission ->
                val isBackgroundLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    permission == Manifest.permission.ACCESS_BACKGROUND_LOCATION
                } else {
                    false
                }
                val isForegroundService = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    permission == Manifest.permission.FOREGROUND_SERVICE
                } else {
                    false
                }
                isBackgroundLocation || isForegroundService
            }

            if (isBasicRequest) {
                // Basic login permissions granted
                Log.i(TAG, "✅ Basic login permissions complete")
                onAllPermissionsGranted()
            } else {
                // Continue comprehensive flow
                when {
                    result.containsKey(Manifest.permission.POST_NOTIFICATIONS) -> checkBatteryOptimization()
                    result.containsKey(Manifest.permission.ACCESS_FINE_LOCATION) ||
                    result.containsKey(Manifest.permission.ACCESS_COARSE_LOCATION) ||
                    result.containsKey(Manifest.permission.ACCESS_BACKGROUND_LOCATION) -> checkForegroundServicePermission()
                    result.containsKey(Manifest.permission.FOREGROUND_SERVICE) -> onAllPermissionsGranted()
                    else -> onAllPermissionsGranted()
                }
            }
        } else {
            Log.i(TAG, "❌ Some permissions denied: $deniedPermissions")

            val permanentlyDenied = deniedPermissions.firstOrNull {
                !ActivityCompat.shouldShowRequestPermissionRationale(fragment.requireActivity(), it)
            }

            if (permanentlyDenied != null) {
                Log.i(TAG, "⚠️ Permission permanently denied: $permanentlyDenied")
                onPermissionsDenied?.invoke(deniedPermissions)
            } else {
                onPermissionsDenied?.invoke(deniedPermissions)
            }
        }
    }

    /**
     * Handle permission result from onRequestPermissionsResult
     */
    fun handlePermissionResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode != PERMISSION_REQUEST_CODE) return

        Log.i(TAG, "onRequestPermissionsResult: permissions = ${permissions.joinToString()}, grantResults = ${grantResults.joinToString()}")

        if (grantResults.isEmpty()) {
            Log.i(TAG, "❌ Permission request cancelled")
            onPermissionsDenied?.invoke(permissions.toList())
            return
        }

        val deniedPermissions = mutableListOf<String>()
        for (i in permissions.indices) {
            if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                deniedPermissions.add(permissions[i])
            }
        }

        if (deniedPermissions.isEmpty()) {
            Log.i(TAG, "✅ All permissions granted via onRequestPermissionsResult")
            hasShownRationale = false // Reset flag

            // Check if this was a basic login permission request or comprehensive flow
            val isBasicRequest = permissions.none { permission ->
                val isBackgroundLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    permission == Manifest.permission.ACCESS_BACKGROUND_LOCATION
                } else {
                    false
                }
                val isForegroundService = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    permission == Manifest.permission.FOREGROUND_SERVICE
                } else {
                    false
                }
                isBackgroundLocation || isForegroundService
            }

            if (isBasicRequest) {
                // Basic login permissions granted
                Log.i(TAG, "✅ Basic login permissions complete")
                onAllPermissionsGranted()
            } else {
                // Continue comprehensive flow based on what was requested
                when {
                    permissions.contains(Manifest.permission.POST_NOTIFICATIONS) -> checkBatteryOptimization()
                    permissions.any { it in listOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    )} -> checkForegroundServicePermission()
                    permissions.contains(Manifest.permission.FOREGROUND_SERVICE) -> onAllPermissionsGranted()
                    else -> onAllPermissionsGranted()
                }
            }
        } else {
            Log.i(TAG, "❌ Some permissions denied: $deniedPermissions")

            val permanentlyDenied = deniedPermissions.firstOrNull {
                !ActivityCompat.shouldShowRequestPermissionRationale(fragment.requireActivity(), it)
            }

            if (permanentlyDenied != null) {
                Log.i(TAG, "⚠️ Permission permanently denied: $permanentlyDenied")
                onPermissionsDenied?.invoke(deniedPermissions)
            } else {
                onPermissionsDenied?.invoke(deniedPermissions)
            }
        }
    }

    /**
     * Open app settings page
     */
    fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        val uri = Uri.fromParts("package", fragment.requireContext().packageName, null)
        intent.data = uri
        fragment.startActivity(intent)
    }

    /**
     * Get a user-friendly message for denied permissions
     */
    fun getDeniedPermissionsMessage(deniedPermissions: List<String>): String {
        val permissionNames = deniedPermissions.mapNotNull { permission ->
            when (permission) {
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION -> "Location"
                Manifest.permission.POST_NOTIFICATIONS -> "Notifications"
                else -> null
            }
        }.distinct()

        return when {
            permissionNames.isEmpty() -> ""
            permissionNames.size == 1 -> "${permissionNames[0]} permission is recommended for better app experience."
            else -> "${permissionNames.joinToString(", ")} permissions are recommended for better app experience."
        }
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
    }
}

