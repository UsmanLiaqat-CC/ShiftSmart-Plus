package com.shiftsmart.plus.periodicAction

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.shiftsmart.plus.database.ShiftSmartPlusDatabase

import com.shiftsmart.plus.services.MyService
import com.shiftsmart.plus.ui.activities.WakeUpActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.shiftsmart.plus.utils.SharedPref
import com.shiftsmart.plus.utils.Utils
import com.shiftsmart.plus.utils.Utils.toLocalDate
import java.text.SimpleDateFormat

import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * AlarmReceiver
 *  - Handles all alarm actions:
 *      • "START_SERVICE": wake UI (optional), start MyService in foreground, then chain TOMORROW.
 *      • "STOP_SERVICE" : request service to stop.
 *      • "CALL_API"     : 5-min heartbeat → do API work, re-arm next tick, and if we’re inside the
 *                         shift window but service isn’t running, (re)arm Today+Tomorrow.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        try {
            Log.i("TAG", "AlarmReceiver: onReceive at ${Utils.getCurrentDateTime()}")

            when (intent?.action) {

                "START_SERVICE" -> {
                    Log.i("AlarmReceiver", "Received START_SERVICE_ALARM")

                    val wakeIntent = Intent(context, WakeUpActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    context.startActivity(wakeIntent)

                    val serviceIntent = Intent(context, MyService::class.java).apply {
                        action = MyService.ACTION_START
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        context.startForegroundService(serviceIntent)
                    else
                        context.startService(serviceIntent)

                    AlarmScheduler.scheduleTomorrowFromPrefs(context)
                }

                "STOP_SERVICE" -> {
                    Log.i("AlarmReceiver", "Received STOP_SERVICE_ALARM")

                    val stopIntent = Intent(context, MyService::class.java).apply {
                        action = MyService.ACTION_STOP
                    }
                    context.startService(stopIntent)

                    AlarmScheduler.scheduleTomorrowFromPrefs(context)
                }

                /*  "CALL_API" -> {
                      Log.i("AlarmReceiver", "🔔 Received CALL_API at ${Utils.getCurrentDateTime()}")

                      val user = SharedPref.getInstance(context)?.getUser()
                      if (user == null) {
                          Log.w("AlarmReceiver", "❌ No user found; skipping CALL_API")
                          return
                      }

                      // Use coroutine to handle async database and service operations
                      CoroutineScope(Dispatchers.IO).launch {
                          try {
                              val db = ShiftSmartPlusDatabase.getInstance(context)
                              val dao = db.dbDao()
                              val sharedPref = SharedPref.getInstance(context)

                              // ✅ IMPROVED APPROACH: Use timestamp for accurate cross-midnight calculations

                              val currentTime = Calendar.getInstance()
                              val formatter = DateTimeFormatter.ofPattern("HH:mm:ss")

                              // Get the current 5-minute boundary (round down to nearest 5 min)
                              val currentMinute = currentTime.get(Calendar.MINUTE)
                              val lastBoundaryMinute = (currentMinute / 5) * 5

                              val targetTime = Calendar.getInstance().apply {
                                  set(Calendar.MINUTE, lastBoundaryMinute)
                                  set(Calendar.SECOND, 0)
                                  set(Calendar.MILLISECOND, 0)
                              }

                              val targetLocalTime = LocalTime.of(
                                  targetTime.get(Calendar.HOUR_OF_DAY),
                                  targetTime.get(Calendar.MINUTE),
                                  0
                              )

                              Log.i("AlarmReceiver", "📍 Target 5-min boundary: $targetLocalTime (Current: ${Utils.getCurrent24HourTime()})")

                              // ✅ STEP 1: Check if current time is within ANY shift period
                              val today = LocalDate.now()
                              val activeMulti = user.multipleTimeTables?.find { mt ->
                                  val s = mt.startDate.toLocalDate()
                                  val e = mt.endDate.toLocalDate()
                                  today in s..e
                              }
                              val effectiveRange = activeMulti?.timetable?.range ?: user.timetable?.range

                              if (effectiveRange != null) {
                                  val todayName = targetTime.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, java.util.Locale.ENGLISH) ?: ""
                                  val yesterdayCalendar = targetTime.clone() as Calendar
                                  yesterdayCalendar.add(Calendar.DAY_OF_YEAR, -1)
                                  val yesterdayName = yesterdayCalendar.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, java.util.Locale.ENGLISH) ?: ""

                                  val todayShift = effectiveRange.find { it.day.equals(todayName, ignoreCase = true) }
                                  val yesterdayShift = effectiveRange.find { it.day.equals(yesterdayName, ignoreCase = true) }

                                  var isWithinShift = false

                                  // Check today's shift
                                  if (todayShift?.start != null && todayShift.end != null) {
                                      isWithinShift = com.shiftsmart.plus.utils.ShiftUtils.isTimeWithinBufferRange(targetTime, todayShift.start, todayShift.end)
                                  }

                                  // If not in today's shift, check yesterday's overnight shift
                                  if (!isWithinShift && yesterdayShift?.start != null && yesterdayShift.end != null) {
                                      isWithinShift = com.shiftsmart.plus.utils.ShiftUtils.isTimeWithinBufferRange(targetTime, yesterdayShift.start, yesterdayShift.end, -1)
                                  }

                                  if (!isWithinShift) {
                                      Log.i("AlarmReceiver", "⏭️ Current time $targetLocalTime is OUTSIDE shift period - skipping record insertion")
                                      scheduleNextAlignedAlarm(context)
                                      return@launch
                                  }

                                  Log.i("AlarmReceiver", "✅ Current time $targetLocalTime is WITHIN shift period - proceeding")
                              }

                              // ✅ STEP 2: Check database FIRST for most accurate last record time
                              val lastSyncTimestamp = sharedPref?.getLastSyncTimestamp() ?: 0L
                              val lastSyncDateTime = sharedPref?.getLastSyncDateTime()
                              val latestDefaultRecord = dao.getLatestDefaultRecord(user._id.toString())

                              // Determine last record time and timestamp
                              val (lastRecordTime, lastRecordTimestamp) = when {
                                  // Priority 1: Use DATABASE record with actual UTC timestamp
                                  latestDefaultRecord?.localTime != null && latestDefaultRecord.time != null -> {
                                      try {
                                          val time = Utils.parseFlexibleTime(latestDefaultRecord.localTime)

                                          // ✅ FIX: Parse the actual UTC timestamp from database to get correct date
                                          val utcFormatter = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                                          utcFormatter.timeZone = java.util.TimeZone.getTimeZone("UTC")
                                          val utcDate = utcFormatter.parse(latestDefaultRecord.time)

                                          if (time != null && utcDate != null) {
                                              // Convert UTC to local timestamp
                                              val localCal = Calendar.getInstance()
                                              localCal.timeInMillis = utcDate.time

                                              Log.i("AlarmReceiver", "📌 Using last record from DATABASE: ${latestDefaultRecord.localTime} (${time}) at ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(localCal.time)}")
                                              Pair(time, localCal.timeInMillis)
                                          } else {
                                              Pair(null, 0L)
                                          }
                                      } catch (e: Exception) {
                                          Log.e("AlarmReceiver", "Failed to parse last record timestamp: ${e.message}")
                                          Pair(null, 0L)
                                      }
                                  }
                                  // Priority 2: Fall back to SharedPreferences if database is empty
                                  lastSyncTimestamp > 0L -> {
                                      val lastCal = Calendar.getInstance().apply { timeInMillis = lastSyncTimestamp }
                                      val time = LocalTime.of(
                                          lastCal.get(Calendar.HOUR_OF_DAY),
                                          lastCal.get(Calendar.MINUTE),
                                          0
                                      )
                                      Log.i("AlarmReceiver", "📌 Using last sync from SharedPreferences (fallback): $lastSyncDateTime (${time})")
                                      Pair(time, lastSyncTimestamp)
                                  }
                                  else -> Pair(null, 0L)
                              }

                              if (lastRecordTime != null && lastRecordTimestamp > 0L) {
                                  // ✅ Normalize BOTH timestamps to :00 seconds for accurate hour:minute comparison
                                  val normalizedLastTimestamp = Calendar.getInstance().apply {
                                      timeInMillis = lastRecordTimestamp
                                      set(Calendar.SECOND, 0)
                                      set(Calendar.MILLISECOND, 0)
                                  }.timeInMillis

                                  val normalizedCurrentTimestamp = Calendar.getInstance().apply {
                                      timeInMillis = targetTime.timeInMillis
                                      set(Calendar.SECOND, 0)
                                      set(Calendar.MILLISECOND, 0)
                                  }.timeInMillis

                                  // ✅ Calculate gap using NORMALIZED TIMESTAMPS (handles overnight shifts correctly)
                                  val gapMillis = normalizedCurrentTimestamp - normalizedLastTimestamp
                                  val minutesDiff = (gapMillis / (1000 * 60)).toInt()

                                  Log.i("AlarmReceiver", "⏱️ Last sync timestamp: $normalizedLastTimestamp (original: $lastRecordTimestamp)")
                                  Log.i("AlarmReceiver", "⏱️ Current timestamp: $normalizedCurrentTimestamp")
                                  Log.i("AlarmReceiver", "⏱️ Gap: $minutesDiff minutes (${gapMillis / 1000} seconds)")

                                  // ✅ CHECK: Is current time a multiple of 5 minutes from last sync?
                                  val remainder = minutesDiff % 5
                                  if (remainder != 0) {
                                      // Check if it's just a small drift (< 2 minutes)
                                      // This can happen due to seconds mismatch from old data
                                      if (remainder <= 2 && minutesDiff >= 5) {
                                          // Small drift - round down and proceed
                                          val adjustedMinutesDiff = (minutesDiff / 5) * 5
                                          Log.w("AlarmReceiver", "⚠️ Small time drift detected (${remainder} min). Adjusting gap from $minutesDiff to $adjustedMinutesDiff minutes")

                                          // Update the last sync time to current boundary to re-align
                                          sharedPref?.saveLastSyncTime(targetLocalTime.toString())

                                          // Proceed with adjusted gap
                                          val numberOfMissingRecords = (adjustedMinutesDiff / 5) - 1
                                          if (numberOfMissingRecords > 0) {
                                              Log.i("AlarmReceiver", "📝 Inserting $numberOfMissingRecords record(s) to fill adjusted $adjustedMinutesDiff minute gap")

                                              val apiIntent = Intent(context, MyService::class.java).apply {
                                                  action = MyService.ACTION_CALL_API
                                                  putExtra("LAST_RECORD_TIME", lastRecordTime.toString())
                                                  putExtra("TARGET_TIME", targetLocalTime.toString())
                                                  putExtra("MISSING_RECORDS", numberOfMissingRecords)
                                              }

                                              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                                                  context.startForegroundService(apiIntent)
                                              else
                                                  context.startService(apiIntent)
                                          }
                                          scheduleNextAlignedAlarm(context)
                                          return@launch
                                      }

                                      // Large misalignment - reschedule to next 5-minute boundary from NOW
                                      Log.w("AlarmReceiver", "⚠️ Large misalignment detected (gap: $minutesDiff min, remainder: $remainder min)")

                                      // Calculate next 5-minute boundary from CURRENT time, not from last sync
                                      val currentCal = Calendar.getInstance()
                                      val currentMinute = currentCal.get(Calendar.MINUTE)

                                      // Round up to next 5-minute boundary
                                      val nextBoundaryMinute = ((currentMinute / 5) + 1) * 5

                                      currentCal.set(Calendar.SECOND, 0)
                                      currentCal.set(Calendar.MILLISECOND, 0)

                                      if (nextBoundaryMinute >= 60) {
                                          // Next boundary is in the next hour
                                          currentCal.add(Calendar.HOUR_OF_DAY, 1)
                                          currentCal.set(Calendar.MINUTE, nextBoundaryMinute - 60)
                                      } else {
                                          currentCal.set(Calendar.MINUTE, nextBoundaryMinute)
                                      }

                                      val nextSyncTime = currentCal.timeInMillis
                                      val nextSyncTimeStr = SimpleDateFormat(
                                          "HH:mm:ss",
                                          Locale.getDefault()
                                      ).format(Date(nextSyncTime))
                                      val timeUntilNext = (nextSyncTime - System.currentTimeMillis()) / 1000 / 60

                                      Log.w("AlarmReceiver", "⏭️ Rescheduling to next 5-minute boundary: $nextSyncTimeStr (in ~$timeUntilNext minutes)")

                                      rescheduleAlarmAtSpecificTime(context, nextSyncTime)
                                      return@launch
                                  }

                                  Log.i("AlarmReceiver", "✅ Gap is a multiple of 5 minutes - proceeding with sync")

                                  if (minutesDiff >= 5) {
                                      // Just insert current record - NO dummy records for missing intervals

                                      Log.i("AlarmReceiver", "✅ Gap of $minutesDiff minutes detected - inserting current record only (no backfill)")

                                      val apiIntent = Intent(context, MyService::class.java).apply {
                                          action = MyService.ACTION_CALL_API
                                          // No LAST_RECORD_TIME or MISSING_RECORDS - just insert current record
                                      }

                                      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                                          context.startForegroundService(apiIntent)
                                      else
                                          context.startService(apiIntent)
                                  } else if (minutesDiff > 0 && minutesDiff < 5) {
                                      Log.i("AlarmReceiver", "⏸️ Gap is $minutesDiff min (< 5 min) → Record already exists at correct boundary")
                                  } else if (minutesDiff < 0) {
                                      Log.i("AlarmReceiver", "⚠\uFE0F Negative gap detected $minutesDiff minutes - inserting current record")

                                      val apiIntent = Intent(context, MyService::class.java).apply {
                                          action = MyService.ACTION_CALL_API
                                      }

                                      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                                          context.startForegroundService(apiIntent)
                                      else
                                          context.startService(apiIntent)
                                  } else {
                                      Log.i("AlarmReceiver", "✅ Record already exists at target time $targetLocalTime")
                                  }
                              } else {
                                  // No previous record - insert first one at current boundary
                                  Log.i("AlarmReceiver", "🆕 No previous record found → Inserting first record at $targetLocalTime")

                                  val apiIntent = Intent(context, MyService::class.java).apply {
                                      action = MyService.ACTION_CALL_API
                                      putExtra("TARGET_TIME", targetLocalTime.toString())
                                      putExtra("MISSING_RECORDS", 1)
                                  }

                                  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                                      context.startForegroundService(apiIntent)
                                  else
                                      context.startService(apiIntent)
                              }

                              // Always schedule next alarm at next 5-minute boundary
                              scheduleNextAlignedAlarm(context)

                          } catch (e: Exception) {
                              Log.e("AlarmReceiver", "❌ Error in CALL_API logic", e)
                              // Still schedule next alarm even if there's an error
                              scheduleNextAlignedAlarm(context)
                          }
                      }
                  }*/

                "CALL_API" -> {
                    Log.i("AlarmReceiver", "🔔 Received CALL_API at ${Utils.getCurrentDateTime()}")

                    val user = SharedPref.getInstance(context)?.getUser()
                    if (user == null) {
                        Log.w("AlarmReceiver", "❌ No user found; skipping CALL_API")
                        return
                    }

                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val db = ShiftSmartPlusDatabase.getInstance(context)
                            val dao = db.dbDao()
                            val sharedPref = SharedPref.getInstance(context)

                            val currentTime = Calendar.getInstance()
                            val currentMinute = currentTime.get(Calendar.MINUTE)
                            val lastBoundaryMinute = (currentMinute / 5) * 5

                            // ✅ Always normalize to nearest LOWER 5-min boundary
                            val targetTime = Calendar.getInstance().apply {
                                set(Calendar.MINUTE, lastBoundaryMinute)
                                set(Calendar.SECOND, 0)
                                set(Calendar.MILLISECOND, 0)
                            }

                            val targetLocalTime = LocalTime.of(
                                targetTime.get(Calendar.HOUR_OF_DAY),
                                targetTime.get(Calendar.MINUTE),
                                0
                            )

                            Log.i(
                                "AlarmReceiver",
                                "📍 Target 5-min boundary: $targetLocalTime (Current: ${Utils.getCurrent24HourTime()})"
                            )

                            // ✅ STEP 1: Ensure current time is within active shift
                            val today = LocalDate.now()
                            val activeMulti = user.multipleTimeTables?.find { mt ->
                                val s = mt.startDate.toLocalDate()
                                val e = mt.endDate.toLocalDate()
                                today in s..e
                            }
                            val effectiveRange = activeMulti?.timetable?.range ?: user.timetable?.range

                            if (effectiveRange != null) {
                                val todayName = targetTime.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.ENGLISH) ?: ""
                                val yesterdayCalendar = targetTime.clone() as Calendar
                                yesterdayCalendar.add(Calendar.DAY_OF_YEAR, -1)
                                val yesterdayName = yesterdayCalendar.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.ENGLISH) ?: ""

                                val todayShift = effectiveRange.find { it.day.equals(todayName, ignoreCase = true) }
                                val yesterdayShift = effectiveRange.find { it.day.equals(yesterdayName, ignoreCase = true) }

                                var isWithinShift = false
                                if (todayShift?.start != null && todayShift.end != null) {
                                    isWithinShift = com.shiftsmart.plus.utils.ShiftUtils.isTimeWithinBufferRange(
                                        targetTime,
                                        todayShift.start,
                                        todayShift.end
                                    )
                                }
                                if (!isWithinShift && yesterdayShift?.start != null && yesterdayShift.end != null) {
                                    isWithinShift = com.shiftsmart.plus.utils.ShiftUtils.isTimeWithinBufferRange(
                                        targetTime,
                                        yesterdayShift.start,
                                        yesterdayShift.end,
                                        -1
                                    )
                                }

                                if (!isWithinShift) {
                                    Log.i("AlarmReceiver", "⏭️ Outside shift period - skipping record insertion")
                                    scheduleNextAlignedAlarm(context)
                                    return@launch
                                }

                                Log.i("AlarmReceiver", "✅ Within shift period - proceeding")
                            }

                            // ✅ STEP 2: Get last recorded timestamp (DB → SharedPref fallback)
                            val lastSyncTimestamp = sharedPref?.getLastSyncTimestamp() ?: 0L
                            val lastSyncDateTime = sharedPref?.getLastSyncDateTime()
                            val latestDefaultRecord = dao.getLatestDefaultRecord(user._id.toString())

                            val (lastRecordTime, lastRecordTimestamp) = when {
                                latestDefaultRecord?.localTime != null && latestDefaultRecord.time != null -> {
                                    try {
                                        val time = Utils.parseFlexibleTime(latestDefaultRecord.localTime)
                                        val utcFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                                        utcFormatter.timeZone = TimeZone.getTimeZone("UTC")
                                        val utcDate = utcFormatter.parse(latestDefaultRecord.time)

                                        if (time != null && utcDate != null) {
                                            val localCal = Calendar.getInstance().apply { timeInMillis = utcDate.time }
                                            Log.i("AlarmReceiver", "📌 Using last DB record: ${latestDefaultRecord.localTime}")
                                            Pair(time, localCal.timeInMillis)
                                        } else Pair(null, 0L)
                                    } catch (e: Exception) {
                                        Log.e("AlarmReceiver", "Failed to parse DB record: ${e.message}")
                                        Pair(null, 0L)
                                    }
                                }

                                lastSyncTimestamp > 0L -> {
                                    val lastCal = Calendar.getInstance().apply { timeInMillis = lastSyncTimestamp }
                                    val time = LocalTime.of(lastCal.get(Calendar.HOUR_OF_DAY), lastCal.get(Calendar.MINUTE), 0)
                                    Log.i("AlarmReceiver", "📌 Using SharedPref fallback: $lastSyncDateTime")
                                    Pair(time, lastSyncTimestamp)
                                }

                                else -> Pair(null, 0L)
                            }

                            // ✅ STEP 3: Compare normalized times and ensure correct boundary
                            if (lastRecordTime != null && lastRecordTimestamp > 0L) {
                                val normalizedLastTimestamp = Calendar.getInstance().apply {
                                    timeInMillis = lastRecordTimestamp
                                    set(Calendar.SECOND, 0)
                                    set(Calendar.MILLISECOND, 0)
                                }.timeInMillis

                                // Force rounding down to prevent drift (e.g., 21:14 → 21:10)
                                val currentCal = Calendar.getInstance().apply {
                                    set(Calendar.SECOND, 0)
                                    set(Calendar.MILLISECOND, 0)
                                }
                                val currentMinute = currentCal.get(Calendar.MINUTE)
                                val roundedMinute = (currentMinute / 5) * 5
                                currentCal.set(Calendar.MINUTE, roundedMinute)
                                val normalizedCurrentTimestamp = currentCal.timeInMillis

                                val gapMillis = normalizedCurrentTimestamp - normalizedLastTimestamp
                                val minutesDiff = (gapMillis / (1000 * 60)).toInt()

                                Log.i("AlarmReceiver", "⏱️ Gap: $minutesDiff minutes")

                                val remainder = minutesDiff % 5
                                if (remainder != 0) {
                                    Log.w("AlarmReceiver", "⚠️ Misaligned: remainder=$remainder → rescheduling next boundary")

                                    val addMinutes = if (currentMinute % 5 == 0) 5 else (5 - currentMinute % 5)
                                    currentCal.add(Calendar.MINUTE, addMinutes)

                                    val nextSyncTime = currentCal.timeInMillis
                                    val nextSyncTimeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                                        .format(Date(nextSyncTime))

                                    Log.w("AlarmReceiver", "⏭️ Rescheduling to next boundary: $nextSyncTimeStr")
                                    rescheduleAlarmAtSpecificTime(context, nextSyncTime)
                                    return@launch
                                }

                                // ✅ If perfectly aligned and >=5 min gap → perform sync
                                if (minutesDiff >= 5) {
                                    Log.i("AlarmReceiver", "✅ Aligned on boundary — calling API")

                                    val apiIntent = Intent(context, MyService::class.java).apply {
                                        action = MyService.ACTION_CALL_API
                                    }
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                                        context.startForegroundService(apiIntent)
                                    else context.startService(apiIntent)
                                } else {
                                    Log.i("AlarmReceiver", "⏸️ Gap < 5 min → skipping duplicate insert")
                                }
                            } else {
                                // ✅ No previous record → insert first record
                                Log.i("AlarmReceiver", "🆕 No previous record found — inserting first one")
                                val apiIntent = Intent(context, MyService::class.java).apply {
                                    action = MyService.ACTION_CALL_API
                                }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                                    context.startForegroundService(apiIntent)
                                else context.startService(apiIntent)
                            }

                            // ✅ Always reschedule next alarm aligned to 5-min boundary
                            scheduleNextAlignedAlarm(context)

                        } catch (e: Exception) {
                            Log.e("AlarmReceiver", "❌ Error in CALL_API", e)
                            scheduleNextAlignedAlarm(context)
                        }
                    }
                }



            }

        } catch (e: Exception) {
            Log.e("TAG", "Error in AlarmReceiver onReceive", e)
        }
    }

    companion object {
        /**
         * scheduleNextAlignedAlarm(...)
         * WHEN: After each CALL_API execution or skip.
         * WHAT: Cancels existing CALL_API PI and arms the next exact 5-min boundary.
         */
        @JvmStatic

        fun scheduleNextAlignedAlarm(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val now = Calendar.getInstance().apply {
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            // Round up to next 5-minute boundary
            val nextAligned = ((now / (5 * 60 * 1000)) + 1) * (5 * 60 * 1000)

            val intent = Intent(context, AlarmReceiver::class.java).apply { action = "CALL_API" }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                1234,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            alarmManager.cancel(pendingIntent)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                val showIntent = PendingIntent.getActivity(
                    context,
                    0,
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = Uri.parse("package:${context.packageName}")
                    },
                    PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(nextAligned, showIntent),
                    pendingIntent
                )
                Log.w("AlarmReceiver", "CALL_API scheduled via setAlarmClock at: ${Date(nextAligned)}")
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    nextAligned,
                    pendingIntent
                )
                Log.d("AlarmReceiver", "Next aligned alarm scheduled at: ${Date(nextAligned)}")
            }
        }

        /**
         * rescheduleAlarmAtSpecificTime(...)
         * WHEN: When current alarm time is not aligned with last sync time (not a multiple of 5 minutes)
         * WHAT: Cancels existing CALL_API PI and schedules alarm at the exact time that aligns with last sync
         */
        @JvmStatic
        fun rescheduleAlarmAtSpecificTime(context: Context, targetTimestamp: Long) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, AlarmReceiver::class.java).apply { action = "CALL_API" }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                1234,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            alarmManager.cancel(pendingIntent)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                val showIntent = PendingIntent.getActivity(
                    context,
                    0,
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = Uri.parse("package:${context.packageName}")
                    },
                    PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(targetTimestamp, showIntent),
                    pendingIntent
                )
                Log.i(
                    "AlarmReceiver",
                    "⏰ Rescheduled CALL_API via setAlarmClock at: ${Date(targetTimestamp)}"
                )
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    targetTimestamp,
                    pendingIntent
                )
                Log.i(
                    "AlarmReceiver",
                    "⏰ Rescheduled CALL_API (aligned) at: ${Date(targetTimestamp)}"
                )
            }
        }

    }
}