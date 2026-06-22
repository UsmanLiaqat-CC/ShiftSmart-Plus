package com.shiftsmart.plus.ui.activities

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.shiftsmart.plus.BuildConfig
import com.shiftsmart.plus.R
import com.shiftsmart.plus.database.DBDao
import com.shiftsmart.plus.database.RecordModel
import com.shiftsmart.plus.databinding.ActivityComplaintAlertBinding
import com.shiftsmart.plus.enums.StatusEnum
import com.shiftsmart.plus.utils.AttendanceSyncManager
import com.shiftsmart.plus.utils.ComplaintAlertNotification
import com.shiftsmart.plus.utils.SharedPref
import com.shiftsmart.plus.utils.Utils
import com.shiftsmart.plus.utils.WifiScanner
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class ComplaintAlertActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TITLE        = "compliance_title"
        const val EXTRA_DESCRIPTION  = "compliance_description"
        const val EXTRA_SENT_AT_MS   = "compliance_sent_at_ms"
        private const val FIFTEEN_MINUTES_MS = 15 * 60 * 1000L
        private const val TAG = "ComplaintAlertActivity"
    }

    @Inject lateinit var dao: DBDao
    @Inject lateinit var syncManager: AttendanceSyncManager

    private lateinit var binding: ActivityComplaintAlertBinding
    private var mediaPlayer: MediaPlayer? = null

    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val granted = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) fetchLocationAndSubmit()
        else showLocationPermissionDeniedDialog()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        binding = ActivityComplaintAlertBinding.inflate(layoutInflater)
        setContentView(binding.root)
        clearComplaintNotification()

        val titleText = intent.getStringExtra(EXTRA_TITLE)?.trim().orEmpty()
        val descText  = intent.getStringExtra(EXTRA_DESCRIPTION)?.trim().orEmpty()
        val sentAtMs  = intent.getLongExtra(EXTRA_SENT_AT_MS, 0L)

        if (titleText.isBlank() || descText.isBlank()) {
            Log.w(TAG, "Missing title/description in compliance full-screen intent")
            finish()
            return
        }

        // Enforce 15-minute window when opened from a notification tap
        if (sentAtMs > 0L && System.currentTimeMillis() - sentAtMs > FIFTEEN_MINUTES_MS) {
            Toast.makeText(this, getString(R.string.response_deadline_passed), Toast.LENGTH_LONG).show()
            finish()
            return
        }

        binding.complaintTitle.text   = titleText
        binding.complaintMessage.text = descText

        // Hide the static details text and footer — replaced by dynamic title/description above
        binding.complaintDetails.visibility = View.GONE
        binding.complaintFooter.visibility  = View.GONE

        binding.complaintButton.text = getString(R.string.acknowledge)

        // Play alarm sound
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioManager.setStreamVolume(
            AudioManager.STREAM_ALARM,
            audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0
        )
        try {
            val rawId = resources.getIdentifier("loud_sound", "raw", packageName)
            mediaPlayer = if (rawId != 0) {
                resources.openRawResourceFd(rawId)?.use { rawFd ->
                    MediaPlayer().apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        )
                        setDataSource(rawFd.fileDescriptor, rawFd.startOffset, rawFd.length)
                        isLooping = true; setVolume(1f, 1f); prepare(); start()
                    }
                }
            } else {
                val assetFd = assets.openFd("loud_sound.mp3")
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    setDataSource(assetFd.fileDescriptor, assetFd.startOffset, assetFd.length)
                    assetFd.close()
                    isLooping = true; setVolume(1f, 1f); prepare(); start()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play loud_sound.mp3", e)
        }

        binding.complaintButton.setOnClickListener { onAcknowledgeClicked() }
    }

    // ── Acknowledge button logic ──────────────────────────────────────────────

    private fun onAcknowledgeClicked() {
        val fineGranted   = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)   == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        when {
            fineGranted || coarseGranted -> fetchLocationAndSubmit()
            else -> {
                // Show rationale then request
                AlertDialog.Builder(this)
                    .setTitle("Location Permission Needed")
                    .setMessage("Your location is required to record the compliance acknowledgment.")
                    .setPositiveButton("Grant") { _, _ ->
                        locationPermLauncher.launch(
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                        )
                    }
                    .setNegativeButton("Skip") { _, _ ->
                        // Proceed without location (0,0)
                        processAcknowledgment(0.0, 0.0)
                    }
                    .show()
            }
        }
    }

    private fun fetchLocationAndSubmit() {
        try {
            LocationServices.getFusedLocationProviderClient(this).lastLocation
                .addOnSuccessListener { loc -> processAcknowledgment(loc?.latitude ?: 0.0, loc?.longitude ?: 0.0) }
                .addOnFailureListener { processAcknowledgment(0.0, 0.0) }
        } catch (e: SecurityException) {
            processAcknowledgment(0.0, 0.0)
        }
    }

    private fun processAcknowledgment(lat: Double, lng: Double) {
        val complianceRecordId = intent.getStringExtra(MainActivity.EXTRA_COMPLIANCE_RECORD_ID) ?: ""
        val sharedPref = SharedPref.getInstance(this) ?: run { finish(); return }
        val user = sharedPref.getUser() ?: run { finish(); return }

        // Switch UI: hide button, show progress panel
        binding.complaintButton.visibility = View.GONE
        binding.progressPanel.visibility   = View.VISIBLE
        setProgress("Fetching location…")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val wifiScanner = WifiScanner(this@ComplaintAlertActivity)

                setProgress("Scanning network…")
                val wifiList = wifiScanner.getFreshWifiList()

                val isLocationGranted = ContextCompat.checkSelfPermission(
                    this@ComplaintAlertActivity, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

                setProgress("Saving record…")
                val record = RecordModel(
                    uuid                 = Utils.generateRandomUuid(),
                    user_id              = user._id,
                    lat                  = lat,
                    lng                  = lng,
                    localTime            = Utils.getCurrent24HourTime(),
                    time                 = Utils.getCurrentUtcTime(),
                    attendanceType       = StatusEnum.compliant.name,
                    attendanceStatus     = Utils.checkInternetAndSetStatus(this@ComplaintAlertActivity),
                    isForceAttendance    = false,
                    isLocation           = isLocationGranted,
                    wifiService          = wifiScanner.isWifiEnabled(),
                    dataService          = Utils.isMobileDataEnabled(this@ComplaintAlertActivity),
                    notification         = Utils.isNotificationPermissionGranted(this@ComplaintAlertActivity),
                    batterySaver         = !Utils.isBatterySaverOn(this@ComplaintAlertActivity),
                    batteryOptimization  = !Utils.isBatteryOptimizationOff(this@ComplaintAlertActivity),
                    wifi_list            = wifiList,
                    deviceName           = Build.MODEL,
                    appVersion           = BuildConfig.VERSION_NAME,
                    deviceType           = "android",
                    complianceRecordId   = complianceRecordId,
                    complianceStatus     = "acknowledged"
                )

                dao.insertRecord(record)
                Log.i(TAG, "Compliance record saved to DB: uuid=${record.uuid}, complianceRecordId=$complianceRecordId")

                if (Utils.isInternetAvailable(this@ComplaintAlertActivity)) {
                    setProgress("Syncing to server…")
                    try {
                        syncManager.startSyncProcess()
                        Log.i(TAG, "Sync triggered via AttendanceSyncManager")
                    } catch (e: Exception) {
                        Log.e(TAG, "API sync failed — record remains in DB for later sync", e)
                    }
                    setProgress("Done!")
                } else {
                    setProgress("Saved offline. Will sync when online.")
                    Log.i(TAG, "Offline — record saved to DB, will sync when online")
                }

                // Brief pause so the user sees the final status message
                kotlinx.coroutines.delay(900)
                withContext(Dispatchers.Main) { stopAndFinish() }

            } catch (e: Exception) {
                Log.e(TAG, "Error processing acknowledgment", e)
                withContext(Dispatchers.Main) {
                    binding.progressPanel.visibility   = View.GONE
                    binding.complaintButton.visibility = View.VISIBLE
                    binding.complaintButton.isEnabled  = true
                    Toast.makeText(this@ComplaintAlertActivity, "Error saving acknowledgment", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** Updates the progress status text on the main thread. */
    private fun setProgress(message: String) {
        runOnUiThread { binding.progressStatusTv.text = message }
    }

    private fun showLocationPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Location Permission Denied")
            .setMessage("Acknowledgment will be recorded without location data.")
            .setPositiveButton("Continue") { _, _ -> processAcknowledgment(0.0, 0.0) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun stopAndFinish() {
        mediaPlayer?.let { if (it.isPlaying) it.stop(); it.release() }
        mediaPlayer = null
        // Return to MainActivity/HomeFragment instead of leaving an empty task.
        // When the app was cold-started via a notification PendingIntent, ComplaintAlertActivity
        // is the only activity in the task. finish() alone would cause Android to restart the
        // app at SplashFragment (with a 3-second delay). Starting MainActivity with
        // FLAG_ACTIVITY_CLEAR_TOP|FLAG_ACTIVITY_SINGLE_TOP brings it to the front if it already
        // exists, or creates it fresh. EXTRA_COMPLAINT_CHECK=true tells SplashFragment to skip
        // its 3-second delay so the user lands on HomeFragment immediately.
        val homeIntent = android.content.Intent(this, MainActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_COMPLAINT_CHECK, true)
        }
        startActivity(homeIntent)
        finish()
    }

    private fun clearComplaintNotification() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(ComplaintAlertNotification.NOTIFICATION_ID)
    }

    override fun onDestroy() {
        mediaPlayer?.let { if (it.isPlaying) it.stop(); it.release() }
        mediaPlayer = null
        super.onDestroy()
    }
}
