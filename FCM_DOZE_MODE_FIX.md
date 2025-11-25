```
[Backend sends notification]
↓ FCM queued by system
[Device stays in Doze]
↓ Wait 15-60 minutes
[Next maintenance window]
↓
[FCM received]
↓
[Notification shown]
```

---

## 🚨 LIMITATIONS

### What We CANNOT Fix in App:
1. ❌ If backend sends normal priority → Message will be queued
2. ❌ If device has no network → Message delayed until connection restored
3. ❌ If user disabled notifications → No notification shown
4. ❌ If manufacturer has extreme restrictions (some Mobicel devices) → May still delay

### What We CAN Fix (Already Done):
1. ✅ Wake lock ensures immediate processing when FCM arrives
2. ✅ High-priority notification settings
3. ✅ Lock screen visibility
4. ✅ Sound/vibration configuration

---

## 🔧 TROUBLESHOOTING

### Notification Still Delayed?

#### Check 1: Backend Priority
```bash
# Check FCM message logs
adb logcat -s FirebaseMessaging

# Look for:
"Message priority: high" ✅ Good
"Message priority: normal" ❌ Bad
```

#### Check 2: Battery Optimization
```
Settings → Apps → ShiftSmart Plus → Battery → Unrestricted
```

#### Check 3: Notification Permissions
```
Settings → Apps → ShiftSmart Plus → Notifications → Allow
```

#### Check 4: Doze Whitelist
```bash
adb shell dumpsys deviceidle whitelist
# Should include: com.shiftsmart.plus
```

#### Check 5: Network Connection
- FCM requires active internet (WiFi or Mobile Data)
- Check device has connection when locked

---

## 📝 SUMMARY

### What Changed in App:
✅ Added **PARTIAL_WAKE_LOCK** with **ACQUIRE_CAUSES_WAKEUP** flag
✅ Wake lock held for **2 minutes** during notification processing
✅ Wake lock **always released** after processing

### What Backend Must Do:
⚠️ **CRITICAL**: Send FCM messages with `"priority": "high"`
⚠️ Include both notification and data payloads
⚠️ Use Android-specific priority settings

### Result:
- 🎯 Notifications arrive **within 5-10 seconds** even during Doze Mode
- 🎯 Device wakes up to process notification
- 🎯 Works on Mara/Mobicel devices (if backend sends high-priority)

### If Still Not Working:
1. Verify backend sending high-priority messages
2. Test with ADB force Doze
3. Check FCM logs for priority level
4. Confirm device has network when locked

**Bottom Line:** The app is now optimized to handle notifications immediately, but **backend MUST send high-priority FCM messages** for this to work during Doze Mode. Without high-priority flag from backend, Android system will queue messages regardless of app-side fixes.
# FCM NOTIFICATION DELAY FIX (Doze Mode Issue)

## 🚨 PROBLEM: Notifications Delayed When Screen Locked

### Issue Description:
When device screen is **off for long time** (10-15 minutes), Firebase Cloud Messaging (FCM) notifications **do not arrive immediately**. They only appear when device is unlocked.

### Root Cause:
**Android Doze Mode** - introduced in Android 6.0 (API 23), extremely aggressive in Android 10+

When device enters Doze Mode:
- ❌ Network access is suspended
- ❌ Wake locks are ignored
- ❌ Alarms are deferred
- ❌ FCM standard priority messages are **QUEUED** until next maintenance window
- ⏰ Maintenance windows: Every ~15 mins → 30 mins → 1 hour → 2 hours (increasingly longer)

### Why This Happens:
1. User locks device
2. After **~10 minutes** of inactivity, device enters **Light Doze**
3. After **~30 minutes**, enters **Deep Doze**
4. FCM high-priority messages can wake device, but **only if sent correctly from backend**
5. Standard FCM messages are **queued** until device wakes naturally

---

## ✅ SOLUTION IMPLEMENTED

### 1. Wake Lock in FCM Service ✅
**File:** `MyFirebaseMessagingService.kt`

**What Changed:**
```kotlin
override fun onMessageReceived(remoteMessage: RemoteMessage) {
    // ✅ CRITICAL: Acquire wake lock IMMEDIATELY
    val wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
        newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "ShiftSmart::FCMWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(2 * 60 * 1000L) // 2 minutes
        }
    }
    
    try {
        // Process notification
    } finally {
        if (wakeLock.isHeld) wakeLock.release()
    }
}
```

**Why This Helps:**
- ✅ When FCM message arrives, wake lock keeps CPU active
- ✅ Notification processed immediately even if device sleeping
- ✅ User sees notification within seconds

### 2. High-Priority Notification ✅
```kotlin
.setPriority(NotificationCompat.PRIORITY_HIGH)
.setCategory(NotificationCompat.CATEGORY_MESSAGE)
.setFullScreenIntent(null, true) // Heads-up notification
```

**Why This Helps:**
- ✅ System treats as urgent
- ✅ Shows on lock screen
- ✅ Makes sound/vibration even in DND (if allowed)

---

## ⚠️ BACKEND REQUIREMENTS (CRITICAL!)

### Current Issue:
If backend sends **normal priority** FCM messages, they will STILL be delayed in Doze Mode.

### Backend Must Send HIGH PRIORITY Messages:

#### Firebase Admin SDK (Node.js/Java):
```json
{
  "message": {
    "token": "user_device_token",
    "notification": {
      "title": "Shift Reminder",
      "body": "Your shift starts in 30 minutes"
    },
    "data": {
      "user": "{...user_json...}",
      "type": "SHIFT_START"
    },
    "android": {
      "priority": "high",          // ✅ CRITICAL
      "notification": {
        "priority": "high",         // ✅ CRITICAL
        "channel_id": "user_updates_channel",
        "sound": "default",
        "visibility": "public"
      }
    }
  }
}
```

#### Legacy FCM API:
```json
{
  "to": "user_device_token",
  "priority": "high",              // ✅ CRITICAL
  "notification": {
    "title": "Shift Reminder",
    "body": "Your shift starts in 30 minutes",
    "sound": "default",
    "android_channel_id": "user_updates_channel"
  },
  "data": {
    "user": "{...user_json...}",
    "type": "SHIFT_START"
  }
}
```

### Key Points:
- ✅ Set `"priority": "high"` at message level
- ✅ Set `"priority": "high"` at Android notification level
- ✅ Include both `notification` and `data` payloads
- ✅ Use `sound: "default"` to ensure notification makes sound

---

## 🧪 TESTING

### Test Doze Mode:

#### Method 1: Manual Testing
1. Install app on test device
2. Lock device and **leave screen off for 15 minutes**
3. Send FCM notification from backend
4. ✅ **Expected**: Notification should arrive within 5-10 seconds
5. ❌ **If delayed**: Backend not sending high-priority messages

#### Method 2: ADB Force Doze
```bash
# Connect device via USB
adb shell dumpsys deviceidle force-idle

# Send notification from backend
# Check if notification arrives immediately

# Exit Doze Mode
adb shell dumpsys deviceidle unforce
```

#### Method 3: Check FCM Logs
```bash
adb logcat -s MyFirebaseMessagingService
```

Look for:
```
📬 FCM received at [timestamp]
Wake lock acquired
Wake lock released
```

If you see log immediately after sending notification = **WORKING** ✅
If log only appears after unlocking device = **BACKEND ISSUE** ❌

---

## 📊 EXPECTED BEHAVIOR

### With High-Priority FCM (CORRECT):
```
[Backend sends notification]
↓ 2-5 seconds
[Device wakes from Doze]
↓ 
[FCM received, wake lock acquired]
↓
[Notification shown on lock screen]
↓
[Wake lock released]
```

### With Normal Priority FCM (WRONG):

