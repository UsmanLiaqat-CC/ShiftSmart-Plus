# WorkManager Consolidation Analysis

## Current State: 3 Overlapping Workers

### 1. **ShiftRestartAlarmManager** (OneTime)
**Purpose:** Start service at specific shift start time  
**Type:** `OneTimeWorkRequest`  
**Worker:** `ShiftStartWorker`  
**Trigger:** Scheduled with delay until next shift  
**What it does:**
- Finds next shift start time from timetable
- Schedules a one-time worker to run at that time
- Checks if service running, starts if not

### 2. **AlarmScheduler → ShiftStatusWorker** (Periodic)
**Purpose:** Check shift status every 15 minutes  
**Type:** `PeriodicWorkRequest` (15 min interval)  
**Worker:** `ShiftStatusWorker`  
**Trigger:** Every 15 minutes continuously  
**What it does:**
- Checks if inside shift window
- Starts service if in shift and not running
- Stops service if out of shift and running

### 3. **ServiceHealthWorkerManager → ShiftStartWorker** (Periodic)
**Purpose:** Service health monitoring every 15 minutes  
**Type:** `PeriodicWorkRequest` (15 min interval)  
**Worker:** `ShiftStartWorker` (same as #1!)  
**Trigger:** Every 15 minutes continuously  
**What it does:**
- Checks if inside shift window
- Checks service health (isRunning + timestamp)
- Restarts service if frozen/dead

## Problems Identified

### ❌ Worker Name Collision
- `ShiftRestartAlarmManager` uses `ShiftStartWorker` (OneTime)
- `ServiceHealthWorkerManager` uses `ShiftStartWorker` (Periodic)
- **Same worker class, different scheduling!**

### ❌ Duplicate Functionality
- Both `ShiftStatusWorker` and `ShiftStartWorker` check shift window
- Both can start/stop service
- Running two 15-min periodic workers doing almost same thing

### ❌ Resource Waste
- Two periodic workers checking the same conditions every 15 minutes
- Double battery drain for redundant checks

## Recommended Consolidation

### Keep This Structure:

```
┌─────────────────────────────────────────────────────────┐
│  ServiceHealthWorkerManager (PERIODIC - 15 min)         │
│  ├─ Worker: ServiceHealthWorker (rename!)               │
│  ├─ Check: Shift window + Service health                │
│  └─ Action: Start/Stop/Restart service                  │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│  ShiftRestartAlarmManager (ONE-TIME)                     │
│  ├─ Worker: ShiftStartWorker (keep existing)            │
│  ├─ Trigger: Exact shift start time                     │
│  └─ Action: Start service at shift start                │
└─────────────────────────────────────────────────────────┐

┌─────────────────────────────────────────────────────────┐
│  AlarmScheduler                                          │
│  ├─ Remove: scheduleWorkManagerShiftBackup()            │
│  └─ Keep: Only AlarmManager scheduling                  │
└─────────────────────────────────────────────────────────┘
```

## Action Plan

### Step 1: Rename Worker to Avoid Collision
**Current:** ShiftStartWorker used by both OneTime and Periodic  
**Fix:** Rename the periodic one to `ServiceHealthWorker`

### Step 2: Remove Duplicate from AlarmScheduler
**Current:** `scheduleWorkManagerShiftBackup()` creates `ShiftStatusWorker`  
**Fix:** Remove this method, rely on `ServiceHealthWorkerManager` instead

### Step 3: Update ShiftRestartAlarmManager
**Current:** Schedules `ShiftStartWorker` as OneTime backup  
**Keep:** This is useful for exact shift start timing

### Step 4: Keep ServiceHealthWorkerManager
**Current:** Most robust implementation  
**Action:** Rename worker class, keep all logic

## Final Architecture

### Purpose Separation:

1. **AlarmManager (AlarmReceiver)** - Primary 5-minute API calls
   - Exact alarms for precision timing
   - Handles CALL_API, START_SERVICE, STOP_SERVICE

2. **ShiftRestartAlarmManager** - Shift start backup
   - OneTime worker at shift start time
   - Backup if AlarmManager fails to start service

3. **ServiceHealthWorkerManager** - Continuous monitoring
   - Periodic 15-min health checks
   - Detects frozen/crashed services
   - Handles overnight shifts

### No More Duplicates:
- ✅ Only ONE periodic worker (ServiceHealthWorker)
- ✅ Clear separation of concerns
- ✅ No worker name collisions
- ✅ Reduced battery usage

## Summary

**Remove:**
- `ShiftStatusWorker` class (delete file)
- `AlarmScheduler.scheduleWorkManagerShiftBackup()` method

**Rename:**
- `ShiftStartWorker` → `ServiceHealthWorker` (for periodic use)

**Keep:**
- `ShiftRestartAlarmManager` for one-time shift start
- `ServiceHealthWorkerManager` for periodic monitoring
- Create new `ShiftStartWorker` for one-time use only

This will eliminate redundancy and battery waste while maintaining reliability!

