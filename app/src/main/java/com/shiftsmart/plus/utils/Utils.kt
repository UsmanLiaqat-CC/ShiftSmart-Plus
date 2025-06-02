package com.shiftsmart.plus.utils

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.getSystemService
import com.google.android.material.snackbar.Snackbar
import com.shiftsmart.plus.database.RecordModel
import com.shiftsmart.plus.enums.ActiveStatusEnum
import com.shiftsmart.plus.models.DataRequest
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.regex.Pattern


/**
 * Created by Usman Liaqat on 28,Jan,2025
 * usmanliaqat@codecoytechnologies.com,
 * CodeCoy Technologies,
 * Lahore, Pakistan.
 */
object Utils {


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
    fun isPermissionRemovedIfUnused(context: Context): Boolean {
        // Check foreground location permission
        val fineLocationGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseLocationGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        // At least one location permission (fine or coarse) should be granted
        val foregroundLocationGranted = fineLocationGranted || coarseLocationGranted

        // Check background location permission (only relevant on Android 10+)
        val backgroundLocationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        // Notification permission (Android 13+)
        val notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        // Return true if any critical permission is missing (possibly removed if unused)
        return !(foregroundLocationGranted && backgroundLocationGranted && notificationsGranted)
    }


    fun isAppBackgroundRestricted(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return !powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun isAutoLaunchAllowed(context: Context): Boolean {
        val packageManager = context.packageManager
        return try {
            val intent = Intent()
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            // Example for Xiaomi
            intent.component = ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
            if (intent.resolveActivity(packageManager) != null) {
                true // If intent can be resolved, assume it's allowed
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }



    fun showSnackBar(msg:String, view: View){
        Snackbar.make(view, msg, Snackbar.LENGTH_SHORT).show()
    }
    fun getCurrentUtcTime(): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault())
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date())
    }

    fun getCurrent24HourTime(): String {
        val format = SimpleDateFormat("HH:mm", Locale.getDefault())
        return format.format(Date())
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
    fun generateRandomFourDigitUuid(): String {
        return (1000..9999).random().toString()
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


     fun getCalendarForShift(day: String?, time: String?, hourOffset: Int): Calendar? {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())

        return try {
            val parsedTime = sdf.parse(time) ?: return null

            val now = Calendar.getInstance()
            val shiftCalendar = Calendar.getInstance()

            // Set the correct hour, minute, and second
            shiftCalendar.time = parsedTime
            shiftCalendar.set(Calendar.YEAR, now.get(Calendar.YEAR))
            shiftCalendar.set(Calendar.MONTH, now.get(Calendar.MONTH))
            shiftCalendar.set(Calendar.DAY_OF_MONTH, now.get(Calendar.DAY_OF_MONTH)) // Set to today
            shiftCalendar.set(Calendar.SECOND, 0)
            shiftCalendar.set(Calendar.MILLISECOND, 0)

            // Ensure we are scheduling for the correct weekday
            val targetDay = getDayOfWeek(day)
            while (shiftCalendar.get(Calendar.DAY_OF_WEEK) != targetDay) {
                shiftCalendar.add(Calendar.DAY_OF_YEAR, 1) // Move to the correct day
            }

            // Apply hour offset (after setting the correct day)
            shiftCalendar.add(Calendar.HOUR_OF_DAY, hourOffset)

            Log.i("getCalendarForShift", "Shift Time: ${shiftCalendar.time}")
            shiftCalendar

        } catch (e: ParseException) {
            Log.e("getCalendarForShift", "Invalid time format", e)
            null
        }
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