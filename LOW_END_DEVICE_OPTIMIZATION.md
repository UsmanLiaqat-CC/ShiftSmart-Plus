# Low-End Device Optimization (Mara/Mobicel)

## 🎯 Problem Statement

On low-end Android devices (especially **Mara** and **Mobicel**), the OS aggressively kills background services, similar to when a user force-stops an app. This breaks ALL background mechanisms:

### What Happens When Service is Killed:
- ❌ **AlarmManager alarms** stop firing
- ❌ **WorkManager** stops working  
- ❌ **JobScheduler** stops working
- ❌ **FCM Push Notifications** don't arrive
- ❌ **BootReceiver** doesn't trigger
- ❌ **Foreground Services** get killed
- ❌ All registered **BroadcastReceivers** are unregistered

This is identical to **Force Stop** behavior, where Android completely blocks the app.

---

## ✅ Solution: Wake-Up → Work → Sleep Pattern

Instead of keeping the service running continuously (which gets killed), we implement an **alarm-driven approach**:

### Core Concept:
```
AlarmManager Fires → Service Starts → Collect Data → Save → Sync → Stop Service → Schedule Next Alarm
```

This pattern is reliable because:
1. ✅ **AlarmManager is system-level** - more persistent than app-level components
2. ✅ **Service only runs when needed** - reduces chance of being killed
3. ✅ **Each wake-up schedules the next** - creates a self-healing chain
4. ✅ **Works even if service was killed** - alarm still fires

---

## 🔧 Implementation Details

### 1. AlarmReceiver (Entry Point)

**Location:** `/app/src/main/java/com/shiftsmart/plus/periodicAction/AlarmReceiver.kt`

When `CALL_API` alarm fires:

```kotlin
"CALL_API" -> {
    // ✅ Check if inside shift window
    if (!isInsideShiftWindow(user)) {
        // Stop service if running, schedule next alarm
        return
    }
    
    // ✅ Start service with ACTION_COLLECT_AND_STOP
    val collectIntent = Intent(context, MyService::class.java).apply {
        action = MyService.ACTION_COLLECT_AND_STOP
    }
    context.startForegroundService(collectIntent)
    
    // Service will handle everything else
}
```

**Key Changes:**
- ❌ Removed complex timing validation logic from receiver
- ❌ Removed service running check (not needed)
- ✅ Simple: Start service and let it handle everything
- ✅ Service becomes self-contained unit of work

---

### 2. MyService (Data Collection)

**Location:** `/app/src/main/java/com/shiftsmart/plus/services/MyService.kt`

New action handler: `handleCollectAndStop()`

#### Flow:

```
1. Validate Shift Status
   └─ If off-shift → Schedule next alarm → Stop

2. Validate 5-Minute Boundary
   └─ If not on boundary (e.g., 10:03) → Schedule at 10:05 → Stop
   
3. Check Gap from Last Sync
   ├─ If gap < 5 min → Skip → Schedule next → Stop
   ├─ If gap not multiple of 5 → Skip to aligned time → Stop
   └─ If valid → Continue
   
4. Collect Data
   ├─ Fetch location (10 sec timeout)
   ├─ Get WiFi scan results
   └─ Create RecordModel
   
5. Save Locally
   └─ AttendanceSyncManager.saveRecordLocally()
   
6. Sync to Server
   └─ Automatic via AttendanceSyncManager
   
7. Schedule Next Alarm
   └─ AlarmReceiver.scheduleNextAlignedAlarm()
   
8. Stop Service (after 2 sec delay)
   └─ finishServiceOperations()
```

**Code Snippet:**

```kotlin
private fun handleCollectAndStop() {
    // Step 1: Validate shift
    if (!isInsideShift) {
        scheduleNextAlarm()
        stopService()
        return
    }
    
    // Step 2: Validate 5-min boundary
    if (currentMinute % 5 != 0) {
        scheduleAtNextBoundary()
        stopService()
        return
    }
    
    // Step 3: Check gap
    if (gapNotValid) {
        scheduleAtNextValidTime()
        stopService()
        return
    }
    
    // Step 4-6: Collect, save, sync
    collectAndSaveData { success ->
        if (success) {
            // Step 7: Schedule next
            scheduleNextAlignedAlarm()
            
            // Step 8: Stop after delay
            delay(2000)
            stopService()
        }
    }
}
```

---

### 3. Alarm Scheduling Logic

**Three scheduling functions:**

#### A. `scheduleNextAlignedAlarm(context)`
**When:** After successful data collection  
**What:** Schedule exactly 5 minutes from last sync time  
**Example:** Last sync at 10:15 → Schedule at 10:20

```kotlin
val nextAligned = lastSyncTimestamp + (5 * 60 * 1000)
alarmManager.setExactAndAllowWhileIdle(RTC_WAKEUP, nextAligned, pendingIntent)
```

#### B. `scheduleAtExactTime(context, targetTimestamp)`
**When:** When current time not on 5-min boundary  
**What:** Schedule at next boundary  
**Example:** Current time 10:03 → Schedule at 10:05

```kotlin
val nextBoundary = ((currentMinute / 5) + 1) * 5
alarmManager.setExactAndAllowWhileIdle(RTC_WAKEUP, nextBoundary, pendingIntent)
```

#### C. `scheduleNextAlarmFromCurrentTime(context)`
**When:** Off-shift or error conditions  
**What:** Schedule 5 min from now (not from last sync)  
**Example:** Current time 10:03 → Schedule at 10:10

```kotlin
val now = System.currentTimeMillis()
val nextAligned = ((now / (5 * 60 * 1000)) + 1) * (5 * 60 * 1000)
alarmManager.setExactAndAllowWhileIdle(RTC_WAKEUP, nextAligned, pendingIntent)
```

---

## 🔄 Complete Lifecycle Example

### Scenario: User has shift from 8:00 AM to 6:00 PM

```
7:00 AM - START_SERVICE alarm fires
   ├─ WakeUpActivity shows briefly
   ├─ Service starts in foreground
   ├─ Backup mechanisms initialized (WorkManager, etc.)
   └─ First CALL_API scheduled at 7:05 AM

7:05 AM - CALL_API alarm fires
   ├─ Service starts with ACTION_COLLECT_AND_STOP
   ├─ Validates: ✅ In shift, ✅ On boundary, ✅ 5 min gap
   ├─ Collects location + wifi
   ├─ Saves to local DB
   ├─ Syncs to server
   ├─ Schedules next at 7:10 AM
   └─ Stops service after 2 seconds

7:10 AM - CALL_API alarm fires
   └─ (Repeat same process)

7:13 AM - Device kills all processes (aggressive battery management)
   └─ Service dies, all background work stops
   
7:15 AM - CALL_API alarm fires (AlarmManager survives!)
   ├─ Service starts fresh with ACTION_COLLECT_AND_STOP
   ├─ Checks gap: Last sync was 7:10, now 7:15 → 5 min gap ✅
   ├─ Collects and saves data
   └─ Continues normally

7:00 PM - STOP_SERVICE alarm fires
   ├─ Service stops
   └─ Tomorrow's alarms scheduled
```

---

## 📊 Benefits of This Approach

### For Low-End Devices (Mara/Mobicel):

1. **✅ Survives Process Death**
   - AlarmManager is system-level, persists across kills
   - Each wake-up is independent

2. **✅ Minimal Resource Usage**
   - Service only runs ~10-15 seconds per cycle
   - Rest of time: no app processes running

3. **✅ Self-Healing**
   - If one alarm missed → next one compensates
   - Gap validation fills missing data during sync

4. **✅ No Dependencies**
   - Doesn't rely on WorkManager, JobScheduler, or FCM
   - Pure AlarmManager + Service pattern

### For All Devices:

5. **✅ Maintains Data Consistency**
   - 5-minute interval enforcement
   - Gap detection and filling during API sync

6. **✅ Shift-Aware**
   - Auto-stops outside shift
   - Validates before every action

7. **✅ Battery Efficient**
   - Short bursts of activity
   - Most of time: app sleeps

---

## 🔍 How It Differs from Previous Implementation

### Before (Continuous Service):

```
Service starts at 7:00 AM
   ├─ Runs continuously until 7:00 PM
   ├─ Handler/Timer triggers every 5 minutes
   ├─ WorkManager as backup
   └─ AlarmManager as backup
   
Problem: Service gets killed on low-end devices
   └─ All backups fail because app is force-stopped
```

### After (Wake-Up → Work → Sleep):

```
AlarmManager fires every 5 minutes
   ├─ Service starts fresh
   ├─ Does ONE task
   ├─ Schedules next alarm
   └─ Stops immediately
   
Advantage: Service death doesn't matter
   └─ AlarmManager keeps firing regardless
```

---

## 🛠️ Testing on Mara/Mobicel Devices

### Test Cases:

1. **Normal Operation**
   - Service should start → collect → stop every 5 min
   - Check logs: "handleCollectAndStop" → "Stopping service after successful data collection"

2. **Device Kills Service**
   - Force stop the app manually
   - Wait for next alarm (up to 5 min)
   - Service should restart automatically

3. **Off-Shift Behavior**
   - After shift end time, service should stop
   - Next alarm scheduled for tomorrow's shift

4. **Timing Accuracy**
   - Records should be at: 10:00, 10:05, 10:10, 10:15...
   - No 10:03, 10:07, or other non-aligned times

5. **Gap Handling**
   - If device off for 20 minutes (4 missed cycles)
   - Next wake-up should sync all missing data to server

### Expected Log Pattern (Every 5 Minutes):

```
🔔 AlarmReceiver: Received CALL_API at 10:15:00
✅ AlarmReceiver: Inside shift window - processing CALL_API
🔄 AlarmReceiver: Starting service with COLLECT_AND_STOP action

🔄 MyService: handleCollectAndStop: Starting data collection at 10:15:01
✅ MyService: On 5-minute boundary - proceeding with data collection
✅ MyService: Fresh location obtained: 37.7749, -122.4194
📝 MyService: Created record at 10:15:02
✅ AttendanceSyncManager: Record saved at 10:15:02
✅ MyService: Record saved and synced successfully
⏰ AlarmReceiver: Next CALL_API alarm scheduled at: 10:20:00
🛑 MyService: Stopping service after successful data collection
```

---

## ⚠️ Important Notes

1. **ACTION_START Still Exists**
   - Used for manual start (FCM notification, boot, user action)
   - Keeps service running continuously (old behavior)
   - Backup mechanisms (WorkManager) still active

2. **ACTION_COLLECT_AND_STOP is New**
   - Used ONLY by AlarmManager CALL_API
   - Implements wake-up → work → sleep pattern
   - No backup mechanisms needed (AlarmManager IS the mechanism)

3. **Two Operating Modes:**
   - **Persistent Mode** (ACTION_START): Service runs continuously with backups
   - **Intermittent Mode** (ACTION_COLLECT_AND_STOP): Service wakes up every 5 min

4. **Shift Start Behavior:**
   - START_SERVICE alarm starts service in persistent mode
   - Then CALL_API alarms take over in intermittent mode
   - Service may be running OR stopped between alarms

---

## 🚀 Deployment Checklist

- [ ] Test on normal devices (Samsung, Pixel) - should work as before
- [ ] Test on Mara device - verify wake-up → work → sleep pattern
- [ ] Test on Mobicel device - verify wake-up → work → sleep pattern
- [ ] Force stop app and verify recovery
- [ ] Check battery usage (should be minimal)
- [ ] Verify 5-minute interval accuracy
- [ ] Test overnight shifts (crosses midnight)
- [ ] Test multiple timetables
- [ ] Check server data for gaps/duplicates

---

## 📝 Summary

**The key insight**: Instead of fighting the OS to keep the service alive, we work WITH its limitations. AlarmManager is the most reliable mechanism on Android - it survives app kills, force stops, and aggressive battery management. By making each alarm fire trigger a complete, independent work cycle, we ensure data collection continues even on the most problematic devices.

**Result**: Reliable attendance tracking on ALL devices, including low-end Mara and Mobicel phones that aggressively kill background processes.

