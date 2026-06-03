package com.shiftsmart.plus.ui.activities

import android.app.NotificationManager
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.shiftsmart.plus.R
import com.shiftsmart.plus.databinding.ActivityComplaintAlertBinding
import com.shiftsmart.plus.utils.ComplaintAlertNotification

class ComplaintAlertActivity : AppCompatActivity() {
    private lateinit var binding: ActivityComplaintAlertBinding
    private var mediaPlayer: MediaPlayer? = null

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

        binding.complaintTitle.text = getString(R.string.complaint_alert_title)
        binding.complaintMessage.text = getString(R.string.complaint_alert_message)
        binding.complaintDetails.text = getString(R.string.complaint_alert_details)
        binding.complaintButton.text = getString(R.string.resolve_compliance)

        // Set device volume to maximum (alarm stream) and play loud_sound.mp3
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)

        // Prefer raw resource loud_sound if available, otherwise fall back to assets.
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
                        rawFd.close()
                        isLooping = true
                        setVolume(1f, 1f)
                        prepare()
                        start()
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
                    prepare()
                    isLooping = true
                    setVolume(1f, 1f)
                    start()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ComplaintAlert", "Failed to play loud_sound.mp3", e)
        }

        binding.complaintButton.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(MainActivity.EXTRA_COMPLAINT_CHECK, true)
            }
            startActivity(intent)
            finish()
        }
    }

    private fun clearComplaintNotification() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(ComplaintAlertNotification.NOTIFICATION_ID)
    }

    override fun onDestroy() {
        mediaPlayer?.let {
            if (it.isPlaying) it.stop()
            it.release()
        }
        mediaPlayer = null
        super.onDestroy()
    }
}
