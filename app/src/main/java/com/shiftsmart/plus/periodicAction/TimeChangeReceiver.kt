package com.shiftsmart.plus.periodicAction

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.shiftsmart.plus.database.DbConstants.RECORD_INTERVAL
import com.shiftsmart.plus.database.RecordModel
import com.shiftsmart.plus.models.TimeRange
import com.shiftsmart.plus.utils.SharedPref
import com.shiftsmart.plus.utils.Utils
import com.shiftsmart.plus.utils.Utils.getCalendarForShift
import com.shiftsmart.plus.utils.Utils.getCurrentDayName
import java.util.Calendar

class TimeChangeReceiver : BroadcastReceiver() {

    private  val TAG = "TimeChangeReceiver"
    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_TIME_TICK == intent.action) {

            // Use existing SharedPref class

            val sharedPref = SharedPref.getInstance(context)

            // Get last execution time, store as string and parse to Long (default to 0)
            // Get last execution time from new key
            val lastExecutionTime = sharedPref?.getLastApiCallTime() ?: 0L
            val currentTime = System.currentTimeMillis()

            // Check if 5 minutes (5 * 60 * 1000 ms) have passed
            if (currentTime - lastExecutionTime >= RECORD_INTERVAL.toLong() * 60 * 1000) {
                // Save current time as last call time
                sharedPref?.saveLastApiCallTime(currentTime)
                val user = sharedPref?.getUser()
                user?.let { it1 ->
                    // Call API or other logic
                    it1.timetable?.range?.let { notifyService(context,it) }
                }
                Log.i("TimeChangeReceiver", "Service triggered at: ${Utils.getCurrentDateTime()} -->currentTime:${currentTime}-->lastTime:${lastExecutionTime}")
            } else {
                Log.i("TimeChangeReceiver", "Less than 5 min passed. Skipping API/Service call. at:${Utils.getCurrentDateTime()}-->currentTime:${currentTime}-->lastTime:${lastExecutionTime}")
            }
        }
    }
    fun notifyService( context: Context,shifts: List<TimeRange>) {
        Log.i(TAG, "notifyService:TimeChangeReceiver: ${Utils.getCurrentDateTime()}")

        val today = getCurrentDayName() // Get today's name, e.g., "Tuesday"

        val todayShift = shifts.find { it.day.equals(today, ignoreCase = true) }

        if (todayShift != null && todayShift.start != null && todayShift.end != null) {
            Log.i(
                TAG,
                "notifyService:TimeChangeReceiver::Today's Shift -> day:${todayShift.day}, start:${todayShift.start}, end:${todayShift.end}"
            )

            val startCalendar = getCalendarForShift(todayShift.day, todayShift.start, -1)
            val endCalendar = getCalendarForShift(todayShift.day, todayShift.end, 1)

            Log.i(
                TAG,
                "notifyService:TimeChangeReceiver::startCalendar: ${startCalendar?.time} --> endCalendar: ${endCalendar?.time}"
            )

            val currentTime = Calendar.getInstance()

            if (startCalendar != null && endCalendar != null) {

                // ✅ Schedule API Worker ONLY IF current time is between shift start & end
                if (currentTime.after(startCalendar) && currentTime.before(endCalendar)) {
                    Log.i(
                        TAG,
                        "notifyService:TimeChangeReceiver::Current time is within shift period, scheduling API Worker."
                    )
                    val intent = Intent("UPDATE_NOTIFICATION")
                    intent.putExtra("message", "Update Data")
                    LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
                } else {

                    Log.i(
                        TAG,
                        "notifyService:TimeChangeReceiver:: Current time is outside shift period, NOT scheduling API Worker."
                    )
                }
            }
        } else {
            Log.i(TAG, "notifyService:TimeChangeReceiver::No shift found for today.")
        }
    }

}

