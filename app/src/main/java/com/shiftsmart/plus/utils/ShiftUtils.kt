package com.shiftsmart.plus.utils

import android.util.Log
import com.shiftsmart.plus.utils.Utils.getCurrentDayName
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

object ShiftUtils {

    /**
     * Checks if current time is within a shift window (with ±1 hour buffer).
     *
     * @param current Current time to check
     * @param start Shift start time in HH:mm format
     * @param end Shift end time in HH:mm format
     * @param shiftDayOffset Number of days to offset the shift (0 = today, -1 = yesterday, etc.)
     * @return true if current time is within the buffered shift window
     */
    fun isTimeWithinBufferRange(
        current: Calendar,
        start: String,
        end: String,
        shiftDayOffset: Int = 0
    ): Boolean {
        val (startH, startM) = start.split(":").map { it.toInt() }
        val (endH, endM) = end.split(":").map { it.toInt() }

        // Copy current so we never mutate caller's calendar
        val now = current.clone() as Calendar
        val dayFormatter = SimpleDateFormat("EEE dd MMM HH:mm:ss", Locale.getDefault())

        val startCal = Calendar.getInstance().apply {
            timeInMillis = current.timeInMillis
            // ✅ Apply day offset FIRST (for checking yesterday's shifts)
            add(Calendar.DAY_OF_YEAR, shiftDayOffset)
            set(Calendar.HOUR_OF_DAY, startH)
            set(Calendar.MINUTE, startM)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.HOUR_OF_DAY, -1) // 1h before shift start (buffer)
        }

        val endCal = Calendar.getInstance().apply {
            timeInMillis = current.timeInMillis
            // ✅ Apply day offset FIRST (for checking yesterday's shifts)
            add(Calendar.DAY_OF_YEAR, shiftDayOffset)
            set(Calendar.HOUR_OF_DAY, endH)
            set(Calendar.MINUTE, endM)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.HOUR_OF_DAY, 1) // 1h after shift end (buffer)
        }

        val shiftLabel = when (shiftDayOffset) {
            0 -> "Today's"
            -1 -> "Yesterday's"
            else -> "${shiftDayOffset} days ago"
        }

        Log.i("ShiftUtils", "  📍 Checking $shiftLabel shift $start → $end")
        Log.i("ShiftUtils", "     Start: ${dayFormatter.format(startCal.time)}")
        Log.i("ShiftUtils", "     End:   ${dayFormatter.format(endCal.time)}")
        Log.i("ShiftUtils", "     Now:   ${dayFormatter.format(now.time)}")

        // ✅ CRITICAL FIX: Detect overnight shift by comparing raw hours (before buffer adjustment)
        val isOvernightShift = endH < startH || (endH == startH && endM < startM)

        if (isOvernightShift) {
            Log.i("ShiftUtils", "     🌙 OVERNIGHT shift detected")

            // End time is next day relative to the shift start day
            endCal.add(Calendar.DAY_OF_YEAR, 1)

            Log.i("ShiftUtils", "     Adjusted End: ${dayFormatter.format(endCal.time)}")
        }

        val inside = now.timeInMillis in startCal.timeInMillis..endCal.timeInMillis

        Log.i("ShiftUtils", "     → ${if (inside) "✅ INSIDE" else "❌ OUTSIDE"}")

        return inside
    }

    fun getCalendarForShift(dayName: String, time: String, offsetHours: Int): Calendar {
        val (hh, mm) = time.split(":").map { it.toInt() }
        val cal = Calendar.getInstance(TimeZone.getDefault())

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
