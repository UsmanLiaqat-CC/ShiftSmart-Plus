# 5-Minute Boundary Check Fix - Stop Infinite Retriggering

## Problem Solved

The alarm was triggering repeatedly every few seconds without actually processing:

```
🔔 Received CALL_API at 12:17:43
⏭️ Current time NOT on 5-min boundary (17m) → skipping and rescheduling

🔔 Received CALL_API at 12:17:48  ← RETRIGGERED (5 seconds later!)
⏭️ Current time NOT on 5-min boundary (17m) → skipping and rescheduling

🔔 Received CALL_API at 12:17:53  ← RETRIGGERED AGAIN!
⏭️ Current time NOT on 5-min boundary (17m) → skipping and rescheduling
```

**Issue**: The alarm kept retriggering because the rescheduling function was not properly cancelling the old alarm.

## Solution Implemented

### 1. **Added Strict Boundary Check at Start**

```kotlin
// ✅ STRICT BOUNDARY CHECK: Only process if current time is on 5-minute boundary
if (currentMinute % 5 != 0) {
    Log.w("AlarmReceiver", "⏭️ Current time NOT on 5-min boundary (${currentMinute}m) → skipping and rescheduling")
    
    // Calculate next 5-minute boundary
    val nextBoundaryMinute = ((currentMinute / 5) + 1) * 5
    val nextBoundaryTime = Calendar.getInstance().apply {
        if (nextBoundaryMinute >= 60) {
            add(Calendar.HOUR_OF_DAY, 1)
            set(Calendar.MINUTE, 0)
        } else {
            set(Calendar.MINUTE, nextBoundaryMinute)
        }
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    
    // Schedule at exact next boundary and CANCEL existing alarms
    scheduleAtExactTime(context, nextBoundaryTime.timeInMillis)
    return@launch
}
```

### 2. **Created `scheduleAtExactTime()` Function**

This function **prevents infinite retriggering** by:
- ✅ Cancelling ALL existing CALL_API alarms first
- ✅ Scheduling ONE single alarm at the exact target time
- ✅ Using the SAME PendingIntent ID (1234) to ensure proper cancellation

```kotlin
@JvmStatic
fun scheduleAtExactTime(context: Context, targetTimestamp: Long) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val intent = Intent(context, AlarmReceiver::class.java).apply { action = "CALL_API" }
    val pendingIntent = PendingIntent.getBroadcast(
        context,
        1234,  // ← SAME ID for proper cancellation
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    // ✅ CRITICAL: Cancel any existing alarms to prevent retriggering
    alarmManager.cancel(pendingIntent)
    Log.i("AlarmReceiver", "🚫 Cancelled existing CALL_API alarms")

    // ✅ Schedule ONE alarm at exact target time
    alarmManager.setExactAndAllowWhileIdle(
        AlarmManager.RTC_WAKEUP,
        targetTimestamp,
        pendingIntent
    )

    val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(targetTimestamp))
    Log.i("AlarmReceiver", "✅ Single CALL_API alarm scheduled at: $timeStr")
}
```

## How It Works Now

### Scenario 1: Alarm triggers at 12:17:43 (NOT on boundary)

```
1. Alarm triggers at 12:17:43
2. Check: 17 % 5 = 2 (NOT on boundary)
3. Calculate next boundary: (17 / 5 + 1) * 5 = 20
4. Cancel ALL existing alarms
5. Schedule ONE alarm at 12:20:00
6. EXIT (no retriggering)

--- Wait until 12:20:00 ---

7. Alarm triggers at 12:20:00
8. Check: 20 % 5 = 0 (ON BOUNDARY ✅)
9. Process API call
10. Schedule next alarm at 12:25:00
```

### Scenario 2: Alarm triggers exactly at 12:15:00 (ON boundary)

```
1. Alarm triggers at 12:15:00
2. Check: 15 % 5 = 0 (ON BOUNDARY ✅)
3. Proceed with API call
4. Check last sync: 12:10:00
5. Gap: 5 minutes (valid ✅)
6. Call MyService.ACTION_CALL_API
7. Schedule next alarm at 12:20:00
```

## Key Differences from Previous Code

| Before | After |
|--------|-------|
| Used `scheduleNextAlignedAlarm()` which didn't cancel properly | Uses `scheduleAtExactTime()` which CANCELS first |
| Alarms kept retriggering every few seconds | Alarm scheduled ONCE at exact boundary |
| No explicit boundary check | Strict boundary check at the start |
| Confusing flow with multiple rescheduling paths | Clear single path: check → cancel → schedule once |

## Expected Log Output

### When NOT on boundary (12:17:43):
```
🔔 Received CALL_API at Mon Nov 17 12:17:43 GMT+02:00 2025
✅ Inside shift window - processing CALL_API
📍 Processing at: 12:17:43 (minute: 17, second: 43)
⏭️ Current time NOT on 5-min boundary (17m) → skipping and rescheduling
⏰ Scheduling next alarm at: 12:20:00
🚫 Cancelled existing CALL_API alarms
✅ Single CALL_API alarm scheduled at: 12:20:00

--- NO MORE LOGS UNTIL 12:20:00 ---
```

### When ON boundary (12:20:00):
```
🔔 Received CALL_API at Mon Nov 17 12:20:00 GMT+02:00 2025
✅ Inside shift window - processing CALL_API
📍 Processing at: 12:20:00 (minute: 20, second: 0)
✅ On 5-minute boundary - proceeding with API call
📌 Using last DB record: 12:15:00
⏱️ Last sync: 12:15:00
⏱️ Current: 12:20:00
⏱️ Gap: 5 minutes
✅ Gap is valid (5 min) — calling API
⏰ Next CALL_API alarm scheduled at: 12:25:00
```

## Benefits

✅ **No infinite retriggering** - Alarm only fires ONCE at scheduled time
✅ **Strict 5-minute boundaries** - Only processes at :00, :05, :10, :15, :20, etc.
✅ **Proper alarm cancellation** - Uses same PendingIntent ID for reliable cancellation
✅ **Clear logging** - Easy to debug what's happening
✅ **Battery efficient** - No repeated alarm triggers

## Testing Checklist

- [ ] Alarm triggers at non-boundary time (e.g., 12:17:43)
- [ ] Logs show "skipping and rescheduling" ONCE
- [ ] NO retriggering before next boundary
- [ ] Alarm fires at next boundary (12:20:00)
- [ ] API call processed successfully
- [ ] Next alarm scheduled for 12:25:00
- [ ] Records maintain exact 5-minute gaps in database

## Files Modified

- `AlarmReceiver.kt`:
  - Added boundary check at start of CALL_API handler
  - Created `scheduleAtExactTime()` function
  - Improved logging with minute and second display

---

**Date Fixed**: November 17, 2025  
**Issue**: Infinite alarm retriggering loop  
**Root Cause**: Alarms were not being properly cancelled before rescheduling  
**Solution**: Created dedicated `scheduleAtExactTime()` function that cancels first, then schedules once

