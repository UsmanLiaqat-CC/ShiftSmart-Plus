package com.shiftsmart.plus.utils

import android.util.Log
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object ShiftUtils {

    private const val TAG = "ShiftUtils"

    fun isTimeWithinBufferRange(currentTime: Calendar, startTimeStr: String, endTimeStr: String, bufferMinutes: Int = 60): Boolean {
        return try {
            val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            val startTime = dateFormat.parse(startTimeStr)
            val endTime = dateFormat.parse(endTimeStr)

            val startCal = Calendar.getInstance().apply {
                time = startTime!!
                set(Calendar.YEAR, currentTime.get(Calendar.YEAR))
                set(Calendar.MONTH, currentTime.get(Calendar.MONTH))
                set(Calendar.DAY_OF_MONTH, currentTime.get(Calendar.DAY_OF_MONTH))
                add(Calendar.MINUTE, -bufferMinutes) // 1 hour before start
            }

            val endCal = Calendar.getInstance().apply {
                time = endTime!!
                set(Calendar.YEAR, currentTime.get(Calendar.YEAR))
                set(Calendar.MONTH, currentTime.get(Calendar.MONTH))
                set(Calendar.DAY_OF_MONTH, currentTime.get(Calendar.DAY_OF_MONTH))
                add(Calendar.MINUTE, bufferMinutes) // 1 hour after end
            }

            if (endCal.before(startCal)) {
                endCal.add(Calendar.DAY_OF_MONTH, 1)
            }

            !currentTime.before(startCal) && !currentTime.after(endCal)

        } catch (e: ParseException) {
            Log.e(TAG, "Error parsing shift times", e)
            false
        }
    }
}
