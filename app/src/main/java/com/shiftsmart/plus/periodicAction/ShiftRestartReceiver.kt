package com.shiftsmart.plus.periodicAction

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.shiftsmart.plus.models.UserModel
import com.shiftsmart.plus.services.MyService
import com.shiftsmart.plus.utils.SharedPref
import com.shiftsmart.plus.utils.ShiftUtils
import com.shiftsmart.plus.utils.Utils
import com.shiftsmart.plus.utils.Utils.isServiceRunning
import com.shiftsmart.plus.utils.Utils.toLocalDate
import java.time.LocalDate
import kotlin.text.compareTo


class ShiftRestartReceiver : BroadcastReceiver() {
    private val TAG = "ShiftRestartReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "⏰ Restart/boot trigger received: $action")

        // Device is going down - nothing to (re)schedule.
        if (action == Intent.ACTION_SHUTDOWN) {
            return
        }

        val appContext = context.applicationContext
        val sharedPref = SharedPref.getInstance(context = appContext)
        val user = sharedPref?.getUser()

        // AlarmManager alarms are wiped on every reboot/quickboot, so we must unconditionally
        // rearm TODAY's and TOMORROW's shift START/STOP alarms here — not just when we happen
        // to already be inside the shift window — otherwise a shift starting later that day
        // would never auto-start until the app is opened manually.
        val defaultShifts = user?.timetable?.range ?: emptyList()
        if (user != null && defaultShifts.isNotEmpty()) {
            Log.i(TAG, "🔁 Rescheduling today+tomorrow shift alarms after boot/quickboot")
            AlarmScheduler.scheduleTodayAndTomorrow(
                appContext,
                defaultShifts,
                user.multipleTimeTables ?: emptyList()
            )
        } else {
            Log.i(TAG, "No logged-in user/timetable found - nothing to schedule")
        }
    }

}
