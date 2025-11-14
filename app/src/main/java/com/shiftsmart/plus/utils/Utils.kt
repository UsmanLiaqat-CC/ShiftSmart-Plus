package com.shiftsmart.plus.utils

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import com.shiftsmart.plus.enums.ActiveStatusEnum
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID


/**
 * Created by Usman Liaqat on 28,Jan,2025
 * usmanliaqat@codecoytechnologies.com,
 * CodeCoy Technologies,
 * Lahore, Pakistan.
 */
object Utils {
    fun String.toLocalDate(): LocalDate {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        return LocalDate.parse(this, formatter)
    }


    fun isGpsAndPermissionEnabled(context: Context): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

        val fineLocationPermission = ActivityCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        )
        val coarseLocationPermission = ActivityCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        )

        val isPermissionGranted = fineLocationPermission == PackageManager.PERMISSION_GRANTED ||
                coarseLocationPermission == PackageManager.PERMISSION_GRANTED

        // ✅ Return TRUE only if BOTH are enabled
        return isGpsEnabled && isPermissionGranted
    }

    fun isCloseAppAfterScreenLockEnabled(context: Context): Boolean {
        return try {
            val value = Settings.Secure.getInt(context.contentResolver, "close_apps_after_screen_lock", 0)
            value == 1
        } catch (e: Settings.SettingNotFoundException) {
            false
        }
    }

    fun isServiceRunning(context: Context,serviceClass: Class<*>): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return activityManager.getRunningServices(Integer.MAX_VALUE).any {
            it.service.className == serviceClass.name
        }
    }


    fun isAppBackgroundRestricted(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.restrictBackgroundStatus != ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED
    }

    fun showSnackBar(msg:String, view: View){
        Snackbar.make(view, msg, Snackbar.LENGTH_SHORT).show()
    }
    fun getCurrentUtcTime(): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault())
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date())
    }
    fun getUTCFromTimestamp(timestamp: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault())
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(timestamp))
    }


    fun getCurrent24HourTime(): String {
        val format = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return format.format(Date())
    }

    /**
     * Parses a time string that can be in either "HH:mm" or "HH:mm:ss" format.
     * This handles cases where LocalTime.toString() omits seconds when they are 0.
     *
     * @param timeString Time string in "HH:mm" or "HH:mm:ss" format
     * @return LocalTime object or null if parsing fails
     */
    fun parseFlexibleTime(timeString: String): java.time.LocalTime? {
        return try {
            // Check the format by counting colons
            val colonCount = timeString.count { it == ':' }

            when (colonCount) {
                1 -> {
                    // Format is HH:mm
                    val formatterWithoutSeconds = DateTimeFormatter.ofPattern("HH:mm")
                    java.time.LocalTime.parse(timeString, formatterWithoutSeconds)
                }
                2 -> {
                    // Format is HH:mm:ss
                    val formatterWithSeconds = DateTimeFormatter.ofPattern("HH:mm:ss")
                    java.time.LocalTime.parse(timeString, formatterWithSeconds)
                }
                else -> {
                    android.util.Log.e("Utils", "Invalid time format: $timeString")
                    null
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("Utils", "Failed to parse time: $timeString", e)
            null
        }
    }
    fun checkInternetAndSetStatus(context: Context): String {
        val isInternetAvailable = isInternetAvailable(context)
        return if (isInternetAvailable) {
            ActiveStatusEnum.online.name
        } else {
            ActiveStatusEnum.offline.name
        }
    }

    fun showPrivacy(context: Context){
        val url = "https://sites.google.com/view/shift-smart/home" // Replace with your URL
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        context.startActivity(intent)
    }
    fun isInternetAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val networkCapabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    fun isMobileDataEnabled(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val networkCapabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

        return networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }
    fun isNotificationPermissionGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }
    fun isBatterySaverOn(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isPowerSaveMode
    }
    // Method to check if battery optimization is turned off
    fun isBatteryOptimizationOff(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            return !pm.isIgnoringBatteryOptimizations(context.packageName)
        }
        return true // Battery optimization check not needed for lower API levels
    }

    fun getCurrentDateTime(): Date {
        return Calendar.getInstance().time
    }
    fun generateRandomUuid(): String {
        return UUID.randomUUID().toString()
    }
    fun rssiToPercentage(rssi: Int): Int {
        val minRssi = -90
        val maxRssi = -30

        // Ensure the RSSI value is within the defined range
        val clampedRssi = rssi.coerceIn(minRssi, maxRssi)

        // Convert the clamped RSSI value to a percentage
        return ((clampedRssi - minRssi) * 100 / (maxRssi - minRssi)).coerceIn(0, 100)
    }

    // Check if the app is ignoring battery optimization
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            return pm.isIgnoringBatteryOptimizations(context.packageName)
        }
        return true
    }



     fun getCurrentDayName(): String {
        val calendar = Calendar.getInstance()
        return SimpleDateFormat("EEEE", Locale.getDefault()).format(calendar.time)
    }
     fun getDayOfWeek(day: String?): Int {

        return when (day!!.lowercase(Locale.getDefault())) {
            "monday" -> Calendar.MONDAY
            "tuesday" -> Calendar.TUESDAY
            "wednesday" -> Calendar.WEDNESDAY
            "thursday" -> Calendar.THURSDAY
            "friday" -> Calendar.FRIDAY
            "saturday" -> Calendar.SATURDAY
            "sunday" -> Calendar.SUNDAY
            else -> -1
        }
    }

}