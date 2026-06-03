package com.shiftsmart.plus.ui.activities

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.shiftsmart.plus.services.MyService

class WakeUpActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_OPEN_COMPLAINT_ALERT = "com.shiftsmart.plus.EXTRA_OPEN_COMPLAINT_ALERT"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        val shouldOpenComplaintAlert = intent?.getBooleanExtra(EXTRA_OPEN_COMPLAINT_ALERT, false) == true
        if (shouldOpenComplaintAlert) {
            val complaintIntent = Intent(this, ComplaintAlertActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(complaintIntent)
            finish()
            return
        }

        val serviceIntent = Intent(this, MyService::class.java).apply {
            action = "START_SERVICE"
        }
        startService(serviceIntent)

        finish() // Close the activity if it's just for screen wake
    }

}