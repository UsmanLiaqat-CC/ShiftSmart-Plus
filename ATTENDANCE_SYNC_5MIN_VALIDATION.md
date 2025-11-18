# 5-Minute Alignment Validation Added to AttendanceSyncManager

## Changes Made

### File: `AttendanceSyncManager.kt`
**Method:** `saveDataLocally()`

Added strict 5-minute alignment validation **before** inserting records into the database.

## Implementation

### New Validation Steps (Before Record Insert):

```kotlin
// ✅ STEP 1: Validate 5-minute alignment (only for default records)
if (record.attendanceType == StatusEnum.default.name) {
    val recordTime = Utils.parseFlexibleTime(record.localTime)
    
    // Check 1: Is time on 5-minute boundary?
    if (recordMinute % 5 != 0) {
        Log.w(TAG, "⏭️ Record NOT on 5-min boundary → SKIPPING")
        return@withLock
    }
    
    // Check 2: Is gap from last record valid?
    val lastSyncTimestamp = SharedPref.getLastSyncTimestamp()
    if (lastSyncTimestamp > 0L) {
        val minutesDiff = (currentTimestamp - lastSyncTimestamp) / (60 * 1000)
        
        when {
            minutesDiff < 5 → SKIP (too soon)
            minutesDiff % 5 != 0 → SKIP (not aligned)
            else → PROCEED (valid gap)
        }
    }
}

// ✅ STEP 2: Check for UTC time duplicate
// ✅ STEP 3: Insert record
```

## Scenarios

### ✅ Scenario 1: Valid Record (On Boundary, Aligned Gap)
```
Last sync: 14:20
New record: 14:25 (minute: 25, on boundary ✅)
Gap: 5 minutes (aligned ✅)
Action: ✅ INSERT record
Log: "✅ Gap is valid (5 min) → proceeding with insert"
```

### ❌ Scenario 2: Invalid Record (Not on Boundary)
```
Last sync: 14:20
New record: 14:26 (minute: 26, NOT on boundary ❌)
Gap: 6 minutes (not aligned ❌)
Action: ⏭️ SKIP insert (but alarm still schedules next)
Log: "⏭️ Record time 14:26 NOT on 5-min boundary (26m) → SKIPPING insert"
```

### ❌ Scenario 3: Invalid Gap (Too Small)
```
Last sync: 14:20
New record: 14:23 (minute: 23, NOT on boundary ❌)
Gap: 3 minutes (< 5 ❌)
Action: ⏸️ SKIP insert
Log: "⏸️ Gap too small (3 min < 5) → SKIPPING insert"
```

### ❌ Scenario 4: Invalid Gap (Not Multiple of 5)
```
Last sync: 14:20
New record: 14:27 (minute: 27, NOT on boundary ❌)
Gap: 7 minutes (not multiple of 5 ❌)
Action: ⚠️ SKIP insert
Log: "⚠️ Gap not multiple of 5 (7 min) → SKIPPING insert"
```

### ✅ Scenario 5: First Record (No Last Sync)
```
Last sync: None
New record: 14:35 (minute: 35, on boundary ✅)
Gap: N/A
Action: ✅ INSERT record
Log: "🆕 No previous record - first sync"
```

## Key Features

### 1. **Only Validates Default Records**
- ARRIVAL/DEPARTURE records bypass validation
- Only auto-generated 5-minute records are checked

### 2. **Dual Validation**
- **Boundary Check:** Record time must be on :00, :05, :10, :15, etc.
- **Gap Check:** Time since last record must be 5, 10, 15, 20... minutes

### 3. **Skip vs. Stop**
- **Skip insert:** Record not saved to database
- **Alarm continues:** Next alarm still scheduled (service doesn't stop)
- **Service continues:** Still inside shift window

### 4. **Early Return**
- Returns before duplicate check if validation fails
- Saves database queries for invalid records
- More efficient than checking duplicates first

## Flow Comparison

### Before (No Validation):
```
Record created at 14:26
  ↓
Check shift window → Inside ✅
  ↓
Check UTC duplicate → No duplicate ✅
  ↓
INSERT record (even though 14:26 is not aligned) ❌
  ↓
Save to SharedPref
  ↓
Sync to API
```

### After (With Validation):
```
Record created at 14:26
  ↓
Check shift window → Inside ✅
  ↓
Check 5-min boundary → 26 % 5 = 1 ❌
  ↓
SKIP insert and return (no database write)
  ↓
Alarm reschedules for 14:30 ✅
```

## Expected Logs

### Valid Record Insert:
```
⏱️ Gap check: Last sync at 14:20:00, Current: 14:25:00, Gap: 5 min
✅ Gap is valid (5 min) → proceeding with insert
💾 Record saved at 14:25:00 | Last sync time updated
```

### Invalid - Not on Boundary:
```
⏭️ Record time 14:26:00 NOT on 5-min boundary (26m) → SKIPPING insert
```

### Invalid - Gap Too Small:
```
⏱️ Gap check: Last sync at 14:20:00, Current: 14:23:00, Gap: 3 min
⏸️ Gap too small (3 min < 5) → SKIPPING insert
```

### Invalid - Gap Not Aligned:
```
⏱️ Gap check: Last sync at 14:20:00, Current: 14:27:00, Gap: 7 min
⚠️ Gap not multiple of 5 (7 min) → SKIPPING insert
```

### First Record:
```
🆕 No previous record - first sync
💾 Record saved at 14:35:00 | Last sync time updated
```

## Benefits

### ✅ Database Integrity
- Only aligned records saved
- No 14:26, 14:31, 14:37 records in database
- Clean 5-minute intervals: 14:20, 14:25, 14:30, 14:35...

### ✅ API Sync Consistency
- Records sent to server are always aligned
- Server-side validation passes easily
- No need for gap filling of misaligned records

### ✅ SharedPref Accuracy
- `lastSyncTimestamp` always reflects valid 5-min boundary
- Future gap calculations remain accurate
- No drift accumulation

### ✅ Reduced Database Writes
- Invalid records caught early
- No unnecessary INSERT operations
- Better performance

### ✅ Alarm Continues
- Skipping record doesn't stop alarms
- Next alarm scheduled normally
- Service continues during shift

## Coordination with AlarmReceiver

Both components now enforce the same rules:

### AlarmReceiver (Before Service Call):
```kotlin
// Check if on boundary
if (currentMinute % 5 != 0) {
    scheduleAtExactTime(context, nextBoundaryTime)
    return // Don't call service
}

// Check gap validity
if (minutesDiff % 5 != 0) {
    scheduleAtExactTime(context, nextValidTime)
    return // Don't call service
}

// Call service if all checks pass
startForegroundService(apiIntent)
```

### AttendanceSyncManager (Before Database Insert):
```kotlin
// Check if on boundary
if (recordMinute % 5 != 0) {
    return@withLock // Don't insert
}

// Check gap validity
if (minutesDiff % 5 != 0) {
    return@withLock // Don't insert
}

// Insert if all checks pass
dao.insertRecord(record)
```

## Testing Checklist

- [ ] Record at 14:25 (boundary, gap=5) → Inserted ✅
- [ ] Record at 14:26 (not boundary) → Skipped ⏭️
- [ ] Record at 14:27 (not boundary, gap=7) → Skipped ⚠️
- [ ] Record at 14:23 (gap < 5) → Skipped ⏸️
- [ ] First record at 14:35 → Inserted ✅
- [ ] ARRIVAL record (any time) → Inserted ✅ (bypasses validation)
- [ ] Service continues after skip
- [ ] Next alarm still scheduled after skip

## Summary

**Added:** Strict 5-minute alignment validation in `saveDataLocally()`  
**Effect:** Records not on 5-minute boundaries or with invalid gaps are **skipped**  
**Service:** Continues running and scheduling alarms (doesn't stop)  
**Database:** Only contains properly aligned records (14:20, 14:25, 14:30...)  
**Coordination:** AlarmReceiver and AttendanceSyncManager now enforce same rules

---

**Date Implemented:** November 18, 2025  
**Purpose:** Prevent misaligned records from being saved to database  
**Impact:** Cleaner data, better API sync, no database bloat

