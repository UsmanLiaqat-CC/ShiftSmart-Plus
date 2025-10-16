package com.shiftsmart.plus.utils

import android.util.Log
import com.shiftsmart.plus.utils.Utils.getCurrentDayName
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

object ShiftUtils {

    fun isTimeWithinBufferRange(current: Calendar, start: String, end: String): Boolean {
        val (startH, startM) = start.split(":").map { it.toInt() }
        val (endH, endM) = end.split(":").map { it.toInt() }

        // Copy current so we never mutate caller's calendar
        val now = current.clone() as Calendar

        val startCal = Calendar.getInstance().apply {
            timeInMillis = current.timeInMillis
            set(Calendar.HOUR_OF_DAY, startH)
            set(Calendar.MINUTE, startM)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.HOUR_OF_DAY, -1) // 1h before shift start
        }

        val endCal = Calendar.getInstance().apply {
            timeInMillis = current.timeInMillis
            set(Calendar.HOUR_OF_DAY, endH)
            set(Calendar.MINUTE, endM)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.HOUR_OF_DAY, 1) // 1h after shift end
        }

        // Handle overnight shifts (e.g. 22:00–02:00)
        if (endCal.timeInMillis <= startCal.timeInMillis) {
            endCal.add(Calendar.DAY_OF_YEAR, 1)
            if (now.timeInMillis < startCal.timeInMillis) {
                now.add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        val inside = now.timeInMillis in startCal.timeInMillis..endCal.timeInMillis

        Log.d(
            "ShiftUtils",
            "→ Check ${SimpleDateFormat("EEE HH:mm:ss", Locale.getDefault()).format(now.time)} " +
                    "within ${SimpleDateFormat("EEE HH:mm:ss", Locale.getDefault()).format(startCal.time)}–" +
                    "${SimpleDateFormat("EEE HH:mm:ss", Locale.getDefault()).format(endCal.time)} = $inside"
        )

        return inside
    }

    fun getCalendarForShift(dayName: String, time: String, offsetHours: Int): Calendar {
        val (hh, mm) = time.split(":").map { it.toInt() }
        val cal = Calendar.getInstance(TimeZone.getDefault())
        val todayName = getCurrentDayName()

        // Move calendar to the correct weekday for this shift
        while (!cal.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.ENGLISH)
                .equals(dayName, ignoreCase = true)) {
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }

        cal.set(Calendar.HOUR_OF_DAY, hh)
        cal.set(Calendar.MINUTE, mm)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.HOUR_OF_DAY, offsetHours)
        return cal
    }
}
