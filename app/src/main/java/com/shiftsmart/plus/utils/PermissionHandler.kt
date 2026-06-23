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
import android.widget.Toast
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
    private var locationDisclosureManager: LocationDisclosureManager? = null

    /**
     * Initialize the permission launcher. Call this in onCreate or onViewCreated
     */
    fun initializePermissionLauncher(launcher: ActivityResultLauncher<Array<String>>) {
        permissionLauncher = launcher
    }

    // ─── Permission-requestability helpers ───────────────────────────────────────

    private fun getPrefs() = fragment.requireContext()
        .getSharedPreferences("shiftsmart_perm_prefs", android.content.Context.MODE_PRIVATE)

    private fun markPermissionsAsAsked(permissions: Array<String>) {
        val editor = getPrefs().edit()
        permissions.forEach { editor.putBoolean("asked_$it", true) }
        editor.apply()
    }

    private fun wasPermissionAsked(permission: String): Boolean =
        getPrefs().getBoolean("asked_$permission", false)

    /**
     * Returns true if the OS will still show a system dialog for [permission]:
     *  - Never asked before (first time)                            → true
     *  - Denied once, no "don't ask again" (rationale = true)       → true
     *  - Permanently denied / "don't ask again" (rationale = false) → false
     */
    fun canRequestPermission(permission: String): Boolean {
        if (ContextCompat.checkSelfPermission(fragment.requireContext(), permission)
                == PackageManager.PERMISSION_GRANTED) return false
        val askedBefore = wasPermissionAsked(permission)
        val showRationale = fragment.shouldShowRequestPermissionRationale(permission)
        // Never asked → first time, request it.
        // Asked + rationale → denied once, OS can still show dialog.
        // Asked + no rationale → permanently denied, OS won't show dialog.
        return !askedBefore || showRationale
    }

    /**
     * Returns true when at least one basic permission (foreground location / notification)
     * is missing AND the OS will still display a runtime dialog for it.
     * Returns false when every missing basic permission is permanently denied
     * → caller should show the custom settings-redirect dialog instead.
     */
    fun hasMissingPermissionsRequestable(): Boolean =
        getBasicPermissions()
            .filter { ContextCompat.checkSelfPermission(fragment.requireContext(), it) != PackageManager.PERMISSION_GRANTED }
            .any { canRequestPermission(it) }

    /**
     * Mark [permissions] as asked and launch the system permission dialog.
     * Using this wrapper ensures canRequestPermission() works correctly on the next call.
     */
    private fun launchPermissions(permissions: Array<String>) {
        markPermissionsAsAsked(permissions)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher?.launch(permissions)
        } else {
            fragment.requestPermissions(permissions, PERMISSION_REQUEST_CODE)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────

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
     * Check if the critical permissions for attendance actions are granted.
     * This includes location (fine, coarse, background) but intentionally excludes
     * POST_NOTIFICATIONS — arrival/departure should proceed even if notifications are denied.
     */
    fun hasCriticalPermissions(): Boolean {
        val critical = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            critical.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        return critical.all { permission ->
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
     * Shows location disclosure BEFORE requesting permissions as per Google Play policy
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

        // Check if location permissions are in the missing list
        val needsLocationPermissions = missingPermissions.any {
            it == Manifest.permission.ACCESS_FINE_LOCATION ||
            it == Manifest.permission.ACCESS_COARSE_LOCATION
        }

        if (needsLocationPermissions) {
            // ⚠️ IMPORTANT: Show location disclosure BEFORE requesting permissions
            Log.i(TAG, "📍 Showing location disclosure before requesting permissions")
            showLocationDisclosureBeforePermissions(missingPermissions)
        } else {
            // No location permissions needed, proceed directly
            Log.i(TAG, "⏳ Requesting basic login permissions: $missingPermissions")
            launchPermissions(missingPermissions.toTypedArray())
        }

        return false
    }

    /**
     * Show location disclosure dialog before requesting permissions
     */
    private fun showLocationDisclosureBeforePermissions(permissionsToRequest: List<String>) {
        locationDisclosureManager = LocationDisclosureManager(
            fragment = fragment,
            onAccepted = {
                Log.i(TAG, "✅ User accepted location disclosure, now requesting permissions")
                // User accepted disclosure, now request permissions
                launchPermissions(permissionsToRequest.toTypedArray())
            },
            onDeclined = {
                Log.i(TAG, "❌ User declined location disclosure")
                Toast.makeText(
                    fragment.requireContext(),
                    "Location access is required for shift tracking and attendance verification",
                    Toast.LENGTH_LONG
                ).show()
                onPermissionsDenied?.invoke(permissionsToRequest)
            }
        )
        locationDisclosureManager?.showDisclosureIfNeeded()
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
     * Shows location disclosure BEFORE requesting permissions as per Google Play policy
     * Step 1: Location Disclosure → Location Permissions → Notification → Battery → Foreground Service
     * @return true if all permissions are already granted, false if requesting
     */
    fun requestPermissions(): Boolean {
        val missingPermissions = getMissingPermissions()

        if (missingPermissions.isEmpty()) {
            Log.i(TAG, "✅ All permissions already granted.")
            onPermissionsGranted()
            return true
        }

        // Check if location permissions are in the missing list
        val needsLocationPermissions = missingPermissions.any {
            it == Manifest.permission.ACCESS_FINE_LOCATION ||
            it == Manifest.permission.ACCESS_COARSE_LOCATION ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && it == Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }

        if (needsLocationPermissions) {
            // ⚠️ IMPORTANT: Show location disclosure BEFORE requesting permissions
            Log.i(TAG, "📍 Showing location disclosure before requesting comprehensive permissions")
            showLocationDisclosureForComprehensiveFlow()
        } else {
            // No location permissions needed, proceed with notification
            Log.i(TAG, "⏳ Starting comprehensive permission flow (no location needed)")
            checkPostNotificationPermission()
        }

        return false
    }

    /**
     * Show location disclosure dialog before starting comprehensive permission flow
     */
    private fun showLocationDisclosureForComprehensiveFlow() {
        locationDisclosureManager = LocationDisclosureManager(
            fragment = fragment,
            onAccepted = {
                Log.i(TAG, "✅ User accepted location disclosure, requesting notification permission first")
                // User accepted disclosure, now start with notification permission
                checkPostNotificationPermission()
            },
            onDeclined = {
                Log.i(TAG, "❌ User declined location disclosure")
                Toast.makeText(
                    fragment.requireContext(),
                    "Location access is required for shift tracking and attendance verification. The app requires 'Allow all the time' permission to function properly.",
                    Toast.LENGTH_LONG
                ).show()
                onPermissionsDenied?.invoke(getMissingPermissions())
            }
        )
        locationDisclosureManager?.showDisclosureIfNeeded()
    }

    // Step 2: Request Location Permissions (Fine + Coarse, then Background separately)
    private fun requestLocationPermissions() {
        Log.i(TAG, "🎯 requestLocationPermissions() called")
        Log.i(TAG, "🎯 Android version: ${Build.VERSION.SDK_INT}")

        // ⚠️ IMPORTANT: On Android 10+, background location MUST be requested separately
        // from foreground location permissions. Requesting them together will cause denial.

        // First check if foreground location permissions are granted
        val hasFineLocation = ContextCompat.checkSelfPermission(
            fragment.requireContext(),
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasCoarseLocation = ContextCompat.checkSelfPermission(
            fragment.requireContext(),
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasForegroundLocation = hasFineLocation && hasCoarseLocation
        Log.i(TAG, "🎯 Foreground location granted: $hasForegroundLocation (Fine: $hasFineLocation, Coarse: $hasCoarseLocation)")

        if (!hasForegroundLocation) {
            // Request foreground location permissions first
            val foregroundPermissions = mutableListOf<String>()
            if (!hasFineLocation) foregroundPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (!hasCoarseLocation) foregroundPermissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)

            Log.i(TAG, "⏳ Requesting FOREGROUND location permissions: $foregroundPermissions")
            try {
                launchPermissions(foregroundPermissions.toTypedArray())
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error requesting foreground location permissions", e)
                onPermissionsDenied?.invoke(foregroundPermissions)
            }
        } else {
            // Foreground location is granted, now check background location
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val hasBackgroundLocation = ContextCompat.checkSelfPermission(
                    fragment.requireContext(),
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

                Log.i(TAG, "🎯 Background location granted: $hasBackgroundLocation")

                if (!hasBackgroundLocation) {
                    // Request background location permission separately
                    Log.i(TAG, "⏳ Requesting BACKGROUND location permission separately")
                    requestBackgroundLocationPermission()
                } else {
                    Log.i(TAG, "✅ All location permissions granted, proceeding to battery optimization")
                    checkBatteryOptimization()
                }
            } else {
                // Android 9 or lower, no background location needed
                Log.i(TAG, "✅ Foreground location granted (Android < 10), proceeding to battery optimization")
                checkBatteryOptimization()
            }
        }
    }

    // Request background location permission (must be done AFTER foreground is granted)
    private fun requestBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Show rationale for background location before requesting
            AlertDialog.Builder(fragment.requireContext())
                .setTitle("Background Location Required")
                .setMessage("To track your shifts even when the app is in the background or closed, we need 'Allow all the time' permission.\n\n" +
                        "This ensures accurate attendance tracking throughout your entire shift.")
                .setPositiveButton("Allow") { dialog, _ ->
                    Log.i(TAG, "✅ User accepted background location rationale")
                    dialog.dismiss()
                    fragment.view?.postDelayed({
                        try {
                            launchPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Error requesting background location", e)
                            // Continue anyway, background location is optional
                            checkBatteryOptimization()
                        }
                    }, 100)
                }
                .setNegativeButton("Skip") { dialog, _ ->
                    Log.i(TAG, "⚠️ User skipped background location")
                    dialog.dismiss()
                    Toast.makeText(
                        fragment.requireContext(),
                        "Background location is recommended for accurate shift tracking",
                        Toast.LENGTH_LONG
                    ).show()
                    // Continue to battery optimization even if skipped
                    checkBatteryOptimization()
                }
                .setCancelable(false)
                .show()
        }
    }

    // Step 3: Check & Request POST_NOTIFICATIONS
    private fun checkPostNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(fragment.requireContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "⏳ Requesting POST_NOTIFICATIONS permission")
            launchPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        } else {
            Log.i(TAG, "✅ POST_NOTIFICATIONS already granted or not required, requesting location permissions")
            requestLocationPermissions()
        }
    }

    // Show rationale dialog before requesting location permissions
    private fun showLocationPermissionRationale() {
        AlertDialog.Builder(fragment.requireContext())
            .setTitle("Location Permission Required")
            .setMessage("ShiftSmart Plus needs location access to:\n\n" +
                    "• Track your attendance during shifts\n" +
                    "• Verify you're at the correct work location\n" +
                    "• Record shift start and end times\n\n" +
                    "The app requires 'Allow all the time' permission to function properly even when the app is in the background.")
            .setPositiveButton("Allow") { dialog, _ ->
                Log.i(TAG, "✅ User accepted location rationale, requesting location permissions")
                dialog.dismiss()
                // Post to handler to ensure dialog is fully dismissed before requesting permissions
                fragment.view?.postDelayed({
                    requestLocationPermissions()
                }, 100)
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                Log.i(TAG, "❌ User declined location rationale")
                dialog.dismiss()
                Toast.makeText(
                    fragment.requireContext(),
                    "Location permission is required for shift tracking",
                    Toast.LENGTH_LONG
                ).show()
                onPermissionsDenied?.invoke(listOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ))
            }
            .setCancelable(false)
            .show()
    }

    // Step 4: Check & Handle Battery Optimization
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
            checkForegroundServicePermission()
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
                checkForegroundServicePermission()
            }
            .setNegativeButton("Cancel") { _, _ ->
                checkForegroundServicePermission()
            }
            .setCancelable(false)
            .show()
    }

    // Step 5: Handle Foreground Service Permission (Android 14+)
    private fun checkForegroundServicePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            ContextCompat.checkSelfPermission(fragment.requireContext(), Manifest.permission.FOREGROUND_SERVICE) != PackageManager.PERMISSION_GRANTED
        ) {
            if (!isForegroundServicePermissionRequested) {
                isForegroundServicePermissionRequested = true
                Log.i(TAG, "⏳ Requesting FOREGROUND_SERVICE permission")
                launchPermissions(arrayOf(Manifest.permission.FOREGROUND_SERVICE))
            }
        } else {
            onAllPermissionsGranted()
        }
    }

    private fun onAllPermissionsGranted() {
        Log.i(TAG, "✅ All permissions granted! Proceeding...")
        onPermissionsGranted()
    }


    /**
     * Handle permission result from ActivityResultLauncher
     */
    fun handlePermissionResult(result: Map<String, Boolean>) {
        Log.i(TAG, "📋 handlePermissionResult called")
        Log.i(TAG, "📋 Result map: $result")

        val deniedPermissions = result.filter { !it.value }.keys.toList()
        val grantedPermissions = result.filter { it.value }.keys.toList()

        Log.i(TAG, "✅ Granted: ${grantedPermissions.joinToString()}")
        Log.i(TAG, "❌ Denied: ${deniedPermissions.joinToString()}")

        if (deniedPermissions.isEmpty()) {
            Log.i(TAG, "✅ All requested permissions granted via launcher")

            // Check what permission was granted and proceed accordingly
            val hasPostNotifications = result.containsKey(Manifest.permission.POST_NOTIFICATIONS)
            val hasForegroundLocation = result.containsKey(Manifest.permission.ACCESS_FINE_LOCATION) ||
                    result.containsKey(Manifest.permission.ACCESS_COARSE_LOCATION)
            val hasBackgroundLocation = result.containsKey(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            val hasForegroundService = result.containsKey(Manifest.permission.FOREGROUND_SERVICE)

            Log.i(TAG, "🔍 Permission check - POST_NOTIFICATIONS: $hasPostNotifications, ForegroundLocation: $hasForegroundLocation, BackgroundLocation: $hasBackgroundLocation, ForegroundService: $hasForegroundService")

            // Continue flow: Notification → Location → Background Location → Battery → Foreground Service
            when {
                hasPostNotifications -> {
                    Log.i(TAG, "🔔 POST_NOTIFICATIONS granted, requesting location permissions")
                    requestLocationPermissions()
                }
                hasBackgroundLocation -> {
                    Log.i(TAG, "📍 Background location granted, proceeding to battery optimization")
                    checkBatteryOptimization()
                }
                hasForegroundLocation -> {
                    Log.i(TAG, "📍 Foreground location granted, requesting background location")
                    // Request background location separately on Android 10+
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        requestBackgroundLocationPermission()
                    } else {
                        // No background location needed on older versions
                        checkBatteryOptimization()
                    }
                }
                hasForegroundService -> {
                    Log.i(TAG, "🎯 Foreground service permission granted, all done!")
                    onAllPermissionsGranted()
                }
                else -> {
                    Log.i(TAG, "⚠️ Unknown permission granted (fallback), completing flow")
                    onAllPermissionsGranted()
                }
            }
        } else {
            Log.i(TAG, "❌ Some permissions denied: $deniedPermissions")

            // If POST_NOTIFICATIONS was the permission being requested and was denied,
            // continue the flow to request location permissions instead of stopping —
            // BUT only if location was NOT already part of this same batch (to avoid asking
            // for location twice when login launches both together and the user denies both).
            val notificationWasDenied = result.containsKey(Manifest.permission.POST_NOTIFICATIONS) &&
                    !result.getOrDefault(Manifest.permission.POST_NOTIFICATIONS, false)
            val locationAlreadyGranted = hasLocationPermissions()
            val locationWasInThisBatch =
                result.containsKey(Manifest.permission.ACCESS_FINE_LOCATION) ||
                result.containsKey(Manifest.permission.ACCESS_COARSE_LOCATION)

            if (notificationWasDenied && !locationAlreadyGranted && !locationWasInThisBatch) {
                Log.i(TAG, "🔔 POST_NOTIFICATIONS denied — requesting location permissions")
                requestLocationPermissions()
                return
            }

            // Location or other permission denied — collect ALL currently missing permissions and notify
            val allMissing = getMissingPermissions()
            Log.i(TAG, "📋 All missing permissions after full flow: $allMissing")
            onPermissionsDenied?.invoke(if (allMissing.isNotEmpty()) allMissing else deniedPermissions)
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

        Log.i(TAG, "📋 onRequestPermissionsResult called")
        Log.i(TAG, "📋 Permissions: ${permissions.joinToString()}")
        Log.i(TAG, "📋 Grant results: ${grantResults.joinToString()}")

        if (grantResults.isEmpty()) {
            Log.i(TAG, "❌ Permission request cancelled")
            onPermissionsDenied?.invoke(permissions.toList())
            return
        }

        val deniedPermissions = mutableListOf<String>()
        val grantedPermissions = mutableListOf<String>()
        for (i in permissions.indices) {
            if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                deniedPermissions.add(permissions[i])
            } else {
                grantedPermissions.add(permissions[i])
            }
        }

        Log.i(TAG, "✅ Granted: ${grantedPermissions.joinToString()}")
        Log.i(TAG, "❌ Denied: ${deniedPermissions.joinToString()}")

        if (deniedPermissions.isEmpty()) {
            Log.i(TAG, "✅ All permissions granted via onRequestPermissionsResult")

            // Check what permission was granted and proceed accordingly
            val hasPostNotifications = permissions.contains(Manifest.permission.POST_NOTIFICATIONS)
            val hasForegroundLocation = permissions.any { it in listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )}
            val hasBackgroundLocation = permissions.contains(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            val hasForegroundService = permissions.contains(Manifest.permission.FOREGROUND_SERVICE)

            Log.i(TAG, "🔍 Permission check - POST_NOTIFICATIONS: $hasPostNotifications, ForegroundLocation: $hasForegroundLocation, BackgroundLocation: $hasBackgroundLocation, ForegroundService: $hasForegroundService")

            // Continue flow: Notification → Location → Background Location → Battery → Foreground Service
            when {
                hasPostNotifications -> {
                    Log.i(TAG, "🔔 POST_NOTIFICATIONS granted, requesting location permissions")
                    requestLocationPermissions()
                }
                hasBackgroundLocation -> {
                    Log.i(TAG, "📍 Background location granted, proceeding to battery optimization")
                    checkBatteryOptimization()
                }
                hasForegroundLocation -> {
                    Log.i(TAG, "📍 Foreground location granted, requesting background location")
                    // Request background location separately on Android 10+
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        requestBackgroundLocationPermission()
                    } else {
                        // No background location needed on older versions
                        checkBatteryOptimization()
                    }
                }
                hasForegroundService -> {
                    Log.i(TAG, "🎯 Foreground service permission granted, all done!")
                    onAllPermissionsGranted()
                }
                else -> {
                    Log.i(TAG, "⚠️ Unknown permission granted (fallback), completing flow")
                    onAllPermissionsGranted()
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

