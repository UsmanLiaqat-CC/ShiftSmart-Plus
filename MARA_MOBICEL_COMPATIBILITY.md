# MARA & MOBICEL DEVICE COMPATIBILITY GUIDE

## 🚨 CRITICAL: These Devices Have Severe Background Restrictions

### Why Mara and Mobicel Devices Are Problematic

**MARA PHONES** (Mara X, Mara S, Mara Z):
- African-manufactured budget Android devices
- Use stock Android 9/10 with aggressive battery optimization
- Kill background services aggressively to save battery
- Alarms may be delayed or completely skipped
- Services often don't restart after being killed

**MOBICEL PHONES** (Various South African models):
- Budget devices with heavily modified Android
- **MOST AGGRESSIVE** power management of any devices
- Background tasks terminated within minutes
- Alarms rarely fire on time
- Services almost never restart automatically
- Known to be the most problematic Android devices for background work

---

## ✅ FIXES IMPLEMENTED

### 1. Device Detection
**File:** `DeviceCompatibilityHelper.kt`

```kotlin
fun isMaraDevice(): Boolean
fun isMobicelDevice(): Boolean
fun isProblematicDevice(): Boolean
```

The app now automatically detects these devices by:
- Manufacturer name (Build.MANUFACTURER)
- Model name (Build.MODEL)
- Brand name (Build.BRAND)

### 2. Extended Wake Locks
**File:** `AlarmReceiver.kt`

**Normal devices:** 3-minute wake lock when alarm fires
**Mara/Mobicel:** 5-minute wake lock when alarm fires

Why: These devices kill processes faster, need longer wake lock to complete work.

### 3. Aggressive WorkManager Strategy
**File:** `PeriodicSyncWorkerManager.kt`

**Normal devices:**
- Standard constraints
- 1-minute retry backoff

**Mara/Mobicel:**
- Relaxed constraints (any network type)
- 30-second retry backoff (faster recovery)
- Immediate one-time work on startup
- Tagged for monitoring

### 4. Device-Specific Battery Optimization
**File:** `BatteryOptimizationHelper.kt`

Multiple fallback intents to open battery settings:
1. Standard battery optimization settings
2. App details page (battery section)
3. Battery saver settings
4. Generic power settings

### 5. Device Information Logging
On service start, the app logs:
- Manufacturer, Brand, Model
- Android version
- Whether device is problematic
- Recommended settings

---

## 📱 USER SETUP INSTRUCTIONS

### FOR MARA DEVICES:

1. **Disable Battery Optimization (CRITICAL)**
   ```
   Settings → Apps → ShiftSmart Plus → Battery → Unrestricted
   ```

2. **Allow Background Data**
   ```
   Settings → Apps → ShiftSmart Plus → Mobile data → Allow background data
   ```

3. **Lock App in Recent Apps**
   - Open recent apps (square button)
   - Find ShiftSmart Plus
   - Tap the lock icon (if available)
   - This prevents the system from killing the app

4. **Keep App in Recent Apps**
   - During your shift, NEVER swipe away ShiftSmart Plus from recent apps
   - This will kill the service immediately

5. **Disable Battery Saver Mode**
   ```
   Settings → Battery → Battery Saver → OFF (during shifts)
   ```

### FOR MOBICEL DEVICES (EXTRA CRITICAL):

⚠️ **MOBICEL DEVICES REQUIRE THE MOST ATTENTION**

1. **Disable Battery Optimization (ABSOLUTELY CRITICAL)**
   ```
   Settings → Apps → ShiftSmart Plus → Battery → Unrestricted
   ```
   Without this, the app WILL NOT work reliably.

2. **Enable Autostart (if available)**
   ```
   Settings → Apps → ShiftSmart Plus → Autostart → Enable
   OR
   Settings → Battery → Autostart → Enable for ShiftSmart Plus
   ```

3. **Allow Background Data**
   ```
   Settings → Apps → ShiftSmart Plus → Mobile data/Data usage
   → Allow background data
   → Allow unrestricted data usage
   ```

4. **Disable Battery Saver Mode COMPLETELY**
   ```
   Settings → Battery → Battery Saver → OFF
   Settings → Battery → Power saving mode → OFF
   ```
   Mobicel's battery saver is extremely aggressive and will break the app.

5. **NEVER Remove from Recent Apps**
   - Keep ShiftSmart Plus in recent apps AT ALL TIMES during shift
   - Swiping it away = Service stops immediately
   - Consider locking the app in recent apps if your device supports it

6. **Disable Adaptive Battery (if available)**
   ```
   Settings → Battery → Adaptive Battery → OFF
   ```

7. **Check Permission Settings**
   ```
   Settings → Apps → ShiftSmart Plus → Permissions
   → Location: Allow all the time
   → Nearby devices: Allow
   ```

---

## 🔧 TECHNICAL DETAILS

### Wake Lock Strategy

```kotlin
// Normal devices
wakeLock.acquire(3 * 60 * 1000L) // 3 minutes

// Mara/Mobicel devices
wakeLock.acquire(5 * 60 * 1000L) // 5 minutes
```

### WorkManager Configuration

```kotlin
// Normal devices
BackoffPolicy.LINEAR, 1L, TimeUnit.MINUTES

// Mara/Mobicel devices
BackoffPolicy.LINEAR, 30L, TimeUnit.SECONDS
+ Immediate one-time work on startup
```

### Alarm Scheduling

```kotlin
// Uses setExactAndAllowWhileIdle() on all devices
// But WorkManager provides 15-min fallback for when alarms are throttled
```

---

## 🧪 TESTING ON MARA/MOBICEL

### Test Checklist:

1. **Initial Setup**
   - [ ] Install app
   - [ ] App detects device type correctly (check logs)
   - [ ] Battery optimization prompt appears
   - [ ] User disables battery optimization

2. **Service Start**
   - [ ] Login and start shift
   - [ ] Service starts immediately
   - [ ] Notification appears
   - [ ] Check logs for "Detected Mara device" or "Detected Mobicel device"

3. **Background Execution**
   - [ ] Lock device screen
   - [ ] Wait 10 minutes
   - [ ] Unlock and check logs
   - [ ] Verify syncs happened every 5 minutes

4. **Doze Mode Test**
   ```bash
   adb shell dumpsys deviceidle force-idle
   # Wait 5 minutes
   adb logcat -s PeriodicSyncWorker
   # Should see WorkManager running
   ```

5. **App Removal from Recent Apps**
   - [ ] Swipe app away from recent apps
   - [ ] Service should restart (may take 15-30 seconds)
   - [ ] Check logs for restart

6. **Device Reboot**
   - [ ] Restart device
   - [ ] Open app after reboot
   - [ ] Service should reschedule alarms
   - [ ] Check logs for BootReceiver activity

---

## 📊 EXPECTED BEHAVIOR

### Ideal Scenario (All Settings Correct):
```
07:00 → Service starts
07:05 → Sync (AlarmManager)
07:10 → Sync (AlarmManager)
07:15 → Sync (AlarmManager or WorkManager fallback)
... every 5 minutes ...
```

### Problematic Scenario (Battery Optimization Enabled):
```
07:00 → Service starts
07:05 → Sync
07:10 → Sync
[Device enters Doze Mode]
07:30 → Sync (WorkManager fallback after delay)
08:00 → Sync (WorkManager fallback)
[Long gaps between syncs]
```

### Worst Case (App Removed from Recent Apps):
```
07:00 → Service starts
07:05 → Sync
[User swipes app away]
07:06 → Service killed
07:21 → WorkManager attempts restart (may fail)
[Service may not restart until user opens app]
```

---

## 🚨 KNOWN LIMITATIONS

### Mara Devices:
- ⚠️ Service may stop if app removed from recent apps
- ⚠️ Alarms may be delayed by 5-10 minutes during Doze Mode
- ⚠️ WorkManager fallback helps but not 100% reliable
- ✅ Generally more reliable than Mobicel

### Mobicel Devices:
- 🔴 **VERY UNRELIABLE** even with all settings correct
- 🔴 Service frequently killed by system
- 🔴 Alarms often delayed by 15+ minutes
- 🔴 WorkManager fallback may also be throttled
- 🔴 User MUST keep app in recent apps
- 🔴 Battery Saver mode breaks everything
- ⚠️ May require user to keep app in foreground during shift

### Workarounds for Mobicel:
1. **Keep screen on during shift** (if feasible)
2. **Open app every 30 minutes** to ensure service is running
3. **Use a second device** if available
4. **Report issues to manager** if app unreliable

---

## 🔍 DEBUGGING

### Check Device Detection:
```bash
adb logcat -s DeviceCompat
```

Look for:
```
I/DeviceCompat: 🔍 Detected Mara device: mara Mara X
I/DeviceCompat: Manufacturer: mara
I/DeviceCompat: Is Problematic: true
```

### Check Wake Lock Usage:
```bash
adb shell dumpsys power | grep ShiftSmart
```

Should see:
```
ShiftSmart::AlarmReceiverWakeLock
MyApp::MyServiceWakelock
```

### Check WorkManager Status:
```bash
adb shell dumpsys jobscheduler | grep shiftsmart
```

### Check Battery Optimization:
```bash
adb shell dumpsys deviceidle whitelist
```

ShiftSmart Plus should be in the whitelist.

---

## 📞 SUPPORT

If service still doesn't work reliably on Mara/Mobicel:

1. **Verify ALL settings are correct** (use checklist above)
2. **Check logs** for specific error messages
3. **Try airplane mode ON/OFF** (resets connectivity)
4. **Restart device**
5. **Reinstall app** (last resort)
6. **Contact support** with device model and logs

### Device Info to Provide:
```
Manufacturer: [from Settings → About Phone]
Model: [from Settings → About Phone]
Android Version: [from Settings → About Phone]
Has Battery Optimization disabled: [Yes/No]
App in Recent Apps: [Yes/No]
Battery Saver Mode: [On/Off]
```

---

## ✅ SUCCESS CRITERIA

App is working correctly on Mara/Mobicel if:
- ✅ Service starts within 1 minute of shift start time
- ✅ Syncs happen at least every 15 minutes (5 min ideal)
- ✅ Service survives screen lock
- ✅ Service survives device Doze Mode
- ✅ Logs show "5-minute sync" or "WorkManager fallback"
- ✅ No gaps longer than 20 minutes in sync history

---

## 🎯 SUMMARY

**Mara Devices:** Moderately problematic, fixes should work 80%+ of time

**Mobicel Devices:** Extremely problematic, fixes help but not guaranteed

**Key to Success:**
1. Disable battery optimization ✅
2. Keep app in recent apps ✅
3. Disable battery saver mode ✅
4. Allow all permissions ✅
5. Be patient with WorkManager fallbacks ✅

**Bottom Line:** These devices are NOT ideal for background task apps, but with proper setup and user awareness, the app can work acceptably.

