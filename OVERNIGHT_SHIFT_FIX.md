# Fixed: Overnight Shift Support - Records Now Continue After Midnight

## Problem Identified

**Shift Configuration:**
- Tuesday: 07:00 - 04:00 (next day)
- With buffer: 06:00 (Tue) - 05:00 (Wed)

**What Was Happening:**
```
Tuesday 07:00 → Records syncing ✅
Tuesday 23:55 → Last record ✅
Wednesday 00:00 → Service stops ❌
Wednesday 00:05 → No record ❌
Wednesday 04:00 → No record ❌
```

**Root Cause:**
The `isInsideShiftWindow()` function only checked **today's shift**. After midnight on Wednesday, it looked for "Wednesday's shift" instead of checking if we're still within **Tuesday's overnight shift** that extends to Wednesday.

## Solution Applied

Updated `isInsideShiftWindow()` to check **both** today's and yesterday's shifts:

### Before (Broken):
```kotlin
private fun isInsideShiftWindow(...): Boolean {
    val todayName = Utils.getCurrentDayName()
    val todayShift = effectiveRange.find { it.day.equals(todayName, ignoreCase = true) }
    
    // ❌ Only checks today's shift
    return ShiftUtils.isTimeWithinBufferRange(now, todayShift.start, todayShift.end)
}
```

### After (Fixed):
```kotlin
private fun isInsideShiftWindow(...): Boolean {
    // ✅ Check today's shift
    val todayName = now.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.ENGLISH)
    val todayShift = effectiveRange.find { it.day.equals(todayName, ignoreCase = true) }
    
    if (todayShift?.start != null && todayShift.end != null) {
        isWithinShift = ShiftUtils.isTimeWithinBufferRange(
            now,
            todayShift.start,
            todayShift.end,
            0 // Today
        )
        if (isWithinShift) return true
    }
    
    // ✅ Check yesterday's shift (for overnight shifts)
    val yesterdayCalendar = now.clone() as Calendar
    yesterdayCalendar.add(Calendar.DAY_OF_YEAR, -1)
    val yesterdayName = yesterdayCalendar.getDisplayName(Calendar.DAY_OF_WEEK, ...)
    val yesterdayShift = effectiveRange.find { it.day.equals(yesterdayName, ...) }
    
    if (yesterdayShift?.start != null && yesterdayShift.end != null) {
        isWithinShift = ShiftUtils.isTimeWithinBufferRange(
            now,
            yesterdayShift.start,
            yesterdayShift.end,
            -1 // Yesterday
        )
        if (isWithinShift) return true
    }
    
    return false
}
```

## How It Works Now

### Scenario: Tuesday Overnight Shift (07:00 - 04:00)

**Tuesday Night (23:00 - 23:59):**
```
Current time: Tuesday 23:55
Check today's shift (Tuesday): 07:00 - 04:00
→ Is 23:55 within 06:00 (Tue) - 05:00 (Wed)? ✅ YES
→ Continue service and sync records ✅
```

**After Midnight (Wednesday 00:00 - 04:00):**
```
Current time: Wednesday 00:05
Check today's shift (Wednesday): Not scheduled or different hours
→ Not within Wednesday's shift ❌

Check yesterday's shift (Tuesday): 07:00 - 04:00 (overnight)
→ Is 00:05 within 06:00 (Tue) - 05:00 (Wed)? ✅ YES
→ Continue service and sync records ✅
```

**Wednesday Morning (After 05:00):**
```
Current time: Wednesday 05:10
Check today's shift (Wednesday): Not active
Check yesterday's shift (Tuesday): 07:00 - 04:00 (overnight)
→ Is 05:10 within 06:00 (Tue) - 05:00 (Wed)? ❌ NO
→ Stop service (shift ended) ✅
```

## Expected Timeline

### Tuesday → Wednesday Overnight Shift (07:00 - 04:00):

```
Tuesday
───────
06:00 → Service starts (1h buffer before shift)
07:00 → Shift officially starts
07:05 → Record synced ✅
07:10 → Record synced ✅
...
23:50 → Record synced ✅
23:55 → Record synced ✅

Wednesday (Next Day - Still Part of Tuesday's Shift)
─────────────────────────────────────────────────────
00:00 → Record synced ✅ (Tuesday's overnight shift continues)
00:05 → Record synced ✅
00:10 → Record synced ✅
...
03:50 → Record synced ✅
03:55 → Record synced ✅
04:00 → Shift officially ends
05:00 → Service stops (1h buffer after shift) ✅
```

## Key Improvements

✅ **Overnight shifts now work correctly** - Service continues after midnight
✅ **Checks both today and yesterday** - Handles cross-day shifts properly
✅ **Uses shiftDayOffset parameter** - Properly passes -1 for yesterday's shift
✅ **Consistent with START_SERVICE logic** - Same pattern used in service start handler
✅ **Clear logging** - Shows which shift (today's or yesterday's) is active

## Technical Details

### ShiftUtils.isTimeWithinBufferRange() Parameters:

```kotlin
fun isTimeWithinBufferRange(
    current: Calendar,
    start: String,
    end: String,
    shiftDayOffset: Int = 0  // ← KEY PARAMETER
): Boolean
```

- `shiftDayOffset = 0`: Check today's shift
- `shiftDayOffset = -1`: Check yesterday's shift (for overnight detection)

### Example Calculation:

**Tuesday's Shift: 19:00 - 04:00 with buffer (18:00 - 05:00)**

When checking at **Wednesday 02:00**:

```kotlin
// Check yesterday's shift (Tuesday)
shiftDayOffset = -1

startCal = Calendar.getInstance().apply {
    add(Calendar.DAY_OF_YEAR, -1)  // Go back to Tuesday
    set(HOUR_OF_DAY, 19)
    set(MINUTE, 0)
    add(HOUR_OF_DAY, -1)  // Buffer: 18:00
}
// Result: Tuesday 18:00

endCal = Calendar.getInstance().apply {
    add(Calendar.DAY_OF_YEAR, -1)  // Go back to Tuesday
    set(HOUR_OF_DAY, 4)
    set(MINUTE, 0)
    add(HOUR_OF_DAY, 1)   // Buffer: 05:00
    add(CALENDAR.DAY_OF_YEAR, 1)  // Overnight shift: add 1 day
}
// Result: Wednesday 05:00

now = Wednesday 02:00
// Is Wednesday 02:00 between Tuesday 18:00 and Wednesday 05:00? ✅ YES
```

## Files Modified

- `AlarmReceiver.kt`: Updated `isInsideShiftWindow()` to check both today and yesterday shifts

## Testing Checklist

- [ ] Service starts at shift start time (with 1h buffer)
- [ ] Records sync every 5 minutes during the day
- [ ] Records continue syncing after midnight (00:00 - 04:00)
- [ ] Service stops at shift end time (with 1h buffer)
- [ ] No gaps in records between 23:55 and 00:05
- [ ] Logs show "Within yesterday's overnight shift" after midnight

---

**Date Fixed**: November 18, 2025  
**Issue**: Overnight shifts stopped syncing after midnight  
**Root Cause**: Only checking today's shift, not yesterday's overnight shift  
**Solution**: Check both today and yesterday shifts with proper day offset

