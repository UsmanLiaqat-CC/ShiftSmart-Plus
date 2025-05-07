package com.shiftsmart.plus.ui.activities

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.shiftsmart.plus.R
import com.shiftsmart.plus.services.MyService

class WakeUpActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        // Optionally, start the service here too
        val intent = Intent(this, MyService::class.java).apply {
            action = "START_SERVICE"
        }
        startService(intent)

        finish() // Close the activity if it's just for screen wake
    }

}