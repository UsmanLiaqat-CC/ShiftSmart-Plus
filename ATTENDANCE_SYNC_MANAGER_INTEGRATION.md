# AttendanceSyncManager Integration

## 🎯 Changes Made

### Problem
The service had duplicate logic for:
- Saving records to the database
- Syncing to API
- Handling online/offline states

This made the code harder to maintain and created inconsistencies.

### Solution
✅ **Centralized all data handling in `AttendanceSyncManager`**

Now the service ONLY:
1. Collects data (location, WiFi, device status)
2. Creates a `RecordModel`
3. Passes it to `AttendanceSyncManager.saveRecordLocally()`

---

## 📋 What AttendanceSyncManager Does

### `saveRecordLocally(record: RecordModel, user: UserModel, callback: (Boolean) -> Unit)`

This single function handles EVERYTHING:

#### ✅ **Step 1: Validation**
- Checks if on 5-minute boundary (10:00, 10:05, 10:10...)
- Checks gap from last record (must be ≥5 minutes)
- Checks gap is multiple of 5 (prevents 7-min, 13-min gaps)
- Checks for duplicate UTC timestamps

#### ✅ **Step 2: Save Locally**
```kotlin
dao.insertRecord(record)
```
- Always saves to local Room database
- Updates last sync timestamp in SharedPreferences

#### ✅ **Step 3: Sync to API**
```kotlin
callApi(user)
```
- **If internet available**: Syncs all pending records to server
- **If offline**: Records stay in local DB, will sync when online
- Handles gap filling (if records were missed)
- Removes synced records from local DB

#### ✅ **Step 4: Callback**
```kotlin
callback(isStillInShift)
```
- Returns `true` if still inside shift (service should continue)
- Returns `false` if off-shift (service should stop)

---

## 🔧 Changes to MyService

### Before (Duplicate Logic):

```kotlin
// ❌ Service had its own saving logic
private fun maybeTriggerApiCallSync() {
    val record = createRecord()
    
    // Save to DB
    dao.insertRecord(record)
    
    // Update SharedPref
    SharedPref.saveLastSyncTime(record.localTime)
    
    // Call API
    attendanceSyncManager.callApi(user)
}
```

### After (Clean & Simple):

```kotlin
// ✅ Service just collects and delegates
private fun handleCollectAndStop() {
    // 1. Collect data
    fetchLocation()
    val record = createRecord()
    
    // 2. Let AttendanceSyncManager handle everything
    attendanceSyncManager.saveRecordLocally(record, user) { isStillInShift ->
        if (isStillInShift) {
            scheduleNextAlarm()
            stopService()
        }
    }
}
```

---

## 📁 Modified Files

### 1. `/app/src/main/java/com/shiftsmart/plus/services/MyService.kt`

#### Removed Functions:
- ❌ `startLocationFetchSync()` - Replaced with inline location fetch
- ❌ `maybeTriggerApiCallSync()` - Logic moved to AttendanceSyncManager

#### Modified Functions:

**`handleCollectAndStop()`**
```kotlin
// Before: 150 lines with save + sync logic
// After: 80 lines - just collect and delegate

serviceScope.launch(Dispatchers.IO) {
    // Fetch location
    if (hasLocationPermissions()) {
        locationHelper.fetchFreshLocation { ... }
    }
    
    // Scan WiFi
    if (wifiEnabled) {
        startWifiScanning()
    }
    
    // Create record
    val record = RecordModel(...)
    
    // Let AttendanceSyncManager handle rest
    attendanceSyncManager.saveRecordLocally(record, user) { isStillInShift ->
        if (isStillInShift) {
            Log.i(TAG, "✅ Record processed (saved ${if (isOnline) "and synced" else "locally"})")
            scheduleNextAlarm()
            stopService()
        } else {
            Log.w(TAG, "Off-shift - stopping")
            finishServiceOperations()
        }
    }
}
```

**`checkAndMaintainService()`**
```kotlin
// Before: Separate location fetch, save, and sync logic
// After: Same clean pattern as handleCollectAndStop()

serviceScope.launch(Dispatchers.IO) {
    // Fetch location
    if (hasLocationPermissions()) { ... }
    
    // Create record
    val record = RecordModel(...)
    
    // Delegate to AttendanceSyncManager
    attendanceSyncManager.saveRecordLocally(record, user) { isStillInShift ->
        if (isStillInShift) {
            updateNotification("Data stored")
        } else {
            finishServiceOperations()
        }
    }
}
```

---

## 🌐 Internet Connectivity Handling

### Automatic Offline/Online Switching

#### When **OFFLINE**:
```
Service creates record
   └─> AttendanceSyncManager.saveRecordLocally()
       ├─> Validates record (5-min boundary, gap, etc.)
       ├─> Saves to local Room database ✅
       ├─> callApi() checks internet
       │   └─> Sees offline → skips API call
       └─> Returns to service: "saved locally"
```

#### When **ONLINE**:
```
Service creates record
   └─> AttendanceSyncManager.saveRecordLocally()
       ├─> Validates record
       ├─> Saves to local database ✅
       ├─> callApi() checks internet
       │   ├─> Sees online → syncs ALL pending records
       │   ├─> Sends to server ✅
       │   └─> Deletes synced records from local DB
       └─> Returns to service: "saved and synced"
```

#### When **Comes Back ONLINE**:
```
Next record creation
   └─> AttendanceSyncManager.saveRecordLocally()
       └─> callApi()
           ├─> Gets all pending records from DB (including old offline ones)
           ├─> Syncs ALL to server ✅
           └─> Clears local DB after successful sync
```

---

## ✅ Benefits

### 1. **Single Source of Truth**
- All save/sync logic in ONE place: `AttendanceSyncManager`
- No duplicate logic scattered across service

### 2. **Automatic Offline Handling**
- Developer doesn't need to check internet in service
- AttendanceSyncManager handles it automatically

### 3. **Easier Maintenance**
- Want to change save logic? Edit ONE function
- Want to change sync logic? Edit ONE function

### 4. **Cleaner Service Code**
- Service focuses on: collect data, delegate to manager
- 100+ lines of code removed from service

### 5. **Consistent Behavior**
- Same validation rules everywhere
- Same offline/online handling everywhere
- Same gap-filling logic everywhere

---

## 🔍 Example Flow

### Scenario: Device offline for 30 minutes, then comes back online

```
10:00 AM - Device goes offline

10:05 AM - Alarm fires
   ├─> Service creates record
   ├─> AttendanceSyncManager saves to local DB ✅
   ├─> callApi() sees offline, skips sync
   └─> Service stops

10:10 AM - Alarm fires (still offline)
   ├─> Service creates record
   ├─> Saved to local DB ✅
   ├─> callApi() sees offline, skips sync
   └─> Service stops

10:15 AM - Alarm fires (still offline)
   └─> (Same pattern)

10:20 AM - Alarm fires (still offline)
   └─> (Same pattern)

10:25 AM - Alarm fires (still offline)
   └─> (Same pattern)

10:30 AM - Device comes back ONLINE, alarm fires
   ├─> Service creates record for 10:30
   ├─> AttendanceSyncManager saves to local DB ✅
   ├─> callApi() sees ONLINE 🌐
   │   ├─> Gets ALL pending records: 10:05, 10:10, 10:15, 10:20, 10:30
   │   ├─> Syncs ALL to server ✅✅✅✅✅
   │   └─> Deletes from local DB after success
   └─> Service stops
```

**Result**: No data lost! All records synced when internet returns.

---

## 🧪 Testing Checklist

- [ ] **Online Mode**: Records saved and synced immediately
- [ ] **Offline Mode**: Records saved locally (check Room DB)
- [ ] **Offline → Online**: All pending records sync when connection returns
- [ ] **5-Min Validation**: Only records on boundary (10:00, 10:05...) are saved
- [ ] **Gap Validation**: Records with <5 min gap are rejected
- [ ] **Shift Validation**: Off-shift records are rejected
- [ ] **Duplicate Prevention**: Same UTC timestamp rejected

---

## 📊 Code Reduction

| File | Before | After | Reduction |
|------|--------|-------|-----------|
| MyService.kt | ~900 lines | ~750 lines | **150 lines removed** |
| Duplicate Logic | Multiple places | 1 place | **Centralized** ✅ |
| Internet Checks | Manual everywhere | Auto in manager | **Simplified** ✅ |

---

## 🎯 Summary

**Key Change**: Service is now a **data collector**, not a data manager.

```
Service Role: Collect → Delegate
   │
   └─> AttendanceSyncManager Role: Validate → Save → Sync
```

This makes the codebase:
- ✅ Cleaner
- ✅ More maintainable
- ✅ Less error-prone
- ✅ Easier to test
- ✅ Automatically handles offline/online states

**Developer Experience**: Just create a record and call `saveRecordLocally()` - everything else is automatic! 🚀

