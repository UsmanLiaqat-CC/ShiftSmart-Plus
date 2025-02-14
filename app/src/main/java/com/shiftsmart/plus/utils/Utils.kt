package com.shiftsmart.plus.utils

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.getSystemService
import com.google.android.material.snackbar.Snackbar
import com.shiftsmart.plus.database.RecordModel
import com.shiftsmart.plus.enums.ActiveStatusEnum
import com.shiftsmart.plus.models.DataRequest
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



    fun isServiceRunning(context: Context,serviceClass: Class<out Service>): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
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
    // Method to check if cache data is available
    fun checkCacheDataAvailability(context: Context,textView: TextView) {
        val cacheDir = context.cacheDir
        val files = cacheDir.listFiles()

        if (files != null && files.isNotEmpty()) {
            // If cache data is available, write 1 to the TextView
            textView.text = "1"
        } else {
            // If no cache data is available, write 0 to the TextView
            textView.text = "0"
        }
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

    fun getCurrentDay(): String {
        return SimpleDateFormat("EEEE", Locale.getDefault()).format(Date())
    }


    fun convertToMillis(time: String): Long {
        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        formatter.timeZone = TimeZone.getDefault() // Ensure timezone is correct

        val calendar = Calendar.getInstance()
        val date = formatter.parse(time)

        if (date != null) {
            calendar.time = date
            // Set current date
            val now = Calendar.getInstance()
            calendar.set(Calendar.YEAR, now.get(Calendar.YEAR))
            calendar.set(Calendar.MONTH, now.get(Calendar.MONTH))
            calendar.set(Calendar.DAY_OF_MONTH, now.get(Calendar.DAY_OF_MONTH))

            return calendar.timeInMillis
        }
        return 0L // Return 0 if parsing fails
    }
    // Check if the app is ignoring battery optimization
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            return pm.isIgnoringBatteryOptimizations(context.packageName)
        }
        return true
    }



}