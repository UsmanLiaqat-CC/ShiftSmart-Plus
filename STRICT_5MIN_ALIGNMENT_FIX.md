# Fixed: Strict 5-Minute Alignment - Skip Non-Aligned Calls

## Problem Identified

The API was being called even when the gap was NOT a multiple of 5 minutes:

### Example 1:
```
Last sync: 14:20
Current:   14:26
Gap:       6 minutes (NOT a multiple of 5!)
Expected:  Skip and schedule for 14:30 (next aligned time)
Actual:    ❌ Called API at 14:26
```

### Example 2:
```
Last sync: 14:45
Current:   14:51
Gap:       6 minutes (NOT a multiple of 5!)
Expected:  Skip and schedule for 14:55 (next aligned time)
Actual:    ❌ Called API at 14:51
```

## Root Cause

The previous logic only checked if `gap >= 5 minutes`, which allowed calls at:
- 6 minutes (14:20 → 14:26) ❌
- 7 minutes (14:20 → 14:27) ❌
- 11 minutes (14:20 → 14:31) ❌

But we need STRICT alignment: only 5, 10, 15, 20, 25... minute gaps!

## New Logic - Three Scenarios

### Scenario 1: No Last Record (Fresh Start)
```
Last sync: None
Current:   14:25 (on 5-min boundary)
Action:    ✅ Call API immediately
```

### Scenario 2: Gap < 5 Minutes
```
Last sync: 14:20
Current:   14:23
Gap:       3 minutes
Action:    ⏸️ Skip (too soon)
Next:      Schedule for 14:25
```

### Scenario 3: Gap is Multiple of 5 (5, 10, 15, 20...)
```
Last sync: 14:20
Current:   14:25
Gap:       5 minutes ✅ (multiple of 5)
Action:    ✅ Call API
Next:      Schedule for 14:30
```

### Scenario 4: Gap is NOT Multiple of 5 (6, 7, 11, 13...)
```
Last sync: 14:20
Current:   14:26
Gap:       6 minutes ❌ (NOT multiple of 5)
Action:    ⏭️ Skip and calculate next aligned time
Calc:      Next multiple = (6 / 5 + 1) * 5 = 10 minutes
Next:      Schedule for 14:30 (14:20 + 10 min)
```

## Code Implementation

```kotlin
when {
    minutesDiff < 5 -> {
        // Too soon
        Log.w("AlarmReceiver", "⏸️ Gap too small ($minutesDiff min < 5) → skipping")
    }
    
    minutesDiff % 5 == 0 -> {
        // Perfect alignment: 5, 10, 15, 20...
        Log.i("AlarmReceiver", "✅ Gap is valid multiple of 5 ($minutesDiff min) — calling API")
        callApiService()
    }
    
    else -> {
        // Not aligned: 6, 7, 11, 13...
        Log.w("AlarmReceiver", "⚠️ Gap not aligned ($minutesDiff min) → skipping to next aligned time")
        
        // Calculate next valid time
        val nextValidMinutes = ((minutesDiff / 5) + 1) * 5
        val nextValidTime = lastSyncTimestamp + (nextValidMinutes * 60 * 1000)
        
        scheduleAtExactTime(context, nextValidTime)
        return@launch
    }
}
```

## Example Timeline

### Perfect Alignment (What Should Happen):
```
14:20 → API called ✅
14:25 → API called ✅
14:30 → API called ✅
14:35 → API called ✅
14:40 → API called ✅
```

### Handling Misalignment:
```
14:20 → API called ✅
14:26 → Alarm triggers (gap = 6 min)
        ⚠️ Not aligned, skip and schedule for 14:30
14:30 → API called ✅ (gap = 10 min from 14:20)
14:35 → API called ✅
14:40 → API called ✅
```

### Handling Large Gaps:
```
14:20 → API called ✅
14:51 → Alarm triggers (gap = 31 min)
        ⚠️ Not aligned (31 % 5 = 1), skip and schedule for 14:55
14:55 → API called ✅ (gap = 35 min from 14:20, which is 7×5)
15:00 → API called ✅
15:05 → API called ✅
```

## Expected Log Output

### When gap is NOT aligned (14:26, gap = 6 min):
```
🔔 Received CALL_API at Mon Nov 17 14:26:00 GMT+02:00 2025
✅ Inside shift window - processing CALL_API
📍 Processing at: 14:26:00 (minute: 26, second: 0)
✅ On 5-minute boundary - proceeding with API call
⏱️ Last sync: 14:20:00
⏱️ Current: 14:26:00
⏱️ Gap: 6 minutes
⚠️ Gap not aligned (6 min) → skipping to next aligned time
⏭️ Next aligned time: 14:30:00 (10 min from last sync)
🚫 Cancelled existing CALL_API alarms
✅ Single CALL_API alarm scheduled at: 14:30:00

--- NO MORE TRIGGERS UNTIL 14:30:00 ---
```

### When gap IS aligned (14:30, gap = 10 min):
```
🔔 Received CALL_API at Mon Nov 17 14:30:00 GMT+02:00 2025
✅ Inside shift window - processing CALL_API
📍 Processing at: 14:30:00 (minute: 30, second: 0)
✅ On 5-minute boundary - proceeding with API call
⏱️ Last sync: 14:20:00
⏱️ Current: 14:30:00
⏱️ Gap: 10 minutes
✅ Gap is valid multiple of 5 (10 min) — calling API
⏰ Next CALL_API alarm scheduled at: 14:35:00
```

## Benefits

✅ **Strict 5-minute intervals** - Only calls API at exact 5, 10, 15, 20... minute gaps
✅ **Automatic realignment** - If alarm drifts (14:26), it self-corrects to 14:30
✅ **No duplicate calls** - Prevents API calls at misaligned times
✅ **Maintains timeline integrity** - Always aligns back to original start time
✅ **Clear logging** - Easy to see why a call was skipped

## Key Formula

```kotlin
// If gap is NOT multiple of 5, calculate next aligned time:
nextValidMinutes = ((currentGap / 5) + 1) * 5
nextValidTime = lastSyncTime + (nextValidMinutes * 60 * 1000)

// Example:
// Last sync: 14:20, Current: 14:26, Gap: 6 min
// nextValidMinutes = ((6 / 5) + 1) * 5 = (1 + 1) * 5 = 10
// nextValidTime = 14:20 + 10 min = 14:30 ✅
```

## Files Modified

- `AlarmReceiver.kt`: Added strict `minutesDiff % 5 == 0` check with realignment logic

---

**Date Fixed**: November 17, 2025  
**Issue**: API calls at misaligned times (14:26 instead of 14:25 or 14:30)  
**Root Cause**: Only checking `gap >= 5` instead of `gap % 5 == 0`  
**Solution**: Added strict modulo check and automatic realignment to next valid time

