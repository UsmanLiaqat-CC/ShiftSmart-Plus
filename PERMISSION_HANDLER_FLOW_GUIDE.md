# PermissionHandler - Comprehensive Permission Flow

## Overview
The `PermissionHandler` class implements a **sequential permission flow** that guides users through all necessary permissions and settings for optimal app performance.

## Permission Flow Sequence

```
┌─────────────────────────────────────────────────────────────┐
│  STEP 1: Check if all permissions granted                  │
│  ├─ YES → Proceed to login immediately ✅                   │
│  └─ NO  → Start sequential permission flow ⬇️               │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│  STEP 2: POST_NOTIFICATIONS (Android 13+)                  │
│  ├─ Request notification permission                         │
│  ├─ Granted → Continue to Step 3 ⬇️                         │
│  └─ Denied → Show rationale or settings dialog             │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│  STEP 3: Battery Optimization                              │
│  ├─ Check if battery optimization is disabled               │
│  ├─ Already disabled → Continue to Step 4 ⬇️                │
│  └─ Enabled → Show dialog to disable                       │
│     ├─ "Go to Settings" → Navigate to battery settings     │
│     └─ "Cancel" → Continue anyway to Step 4                │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│  STEP 4: Battery Saver Mode                                │
│  ├─ Check if battery saver mode is OFF                     │
│  ├─ OFF → Continue to Step 5 ⬇️                             │
│  └─ ON → Show dialog to disable                            │
│     ├─ "Go to Settings" → Navigate to battery saver        │
│     └─ "Cancel" → Continue anyway to Step 5                │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│  STEP 5: Location Permissions                              │
│  ├─ ACCESS_FINE_LOCATION                                   │
│  ├─ ACCESS_COARSE_LOCATION                                 │
│  ├─ ACCESS_BACKGROUND_LOCATION (Android 10+)               │
│  │                                                           │
│  ├─ Check if rationale needed                              │
│  │  ├─ First time asking → Request directly               │
│  │  └─ Previously denied → Show rationale dialog           │
│  │                                                           │
│  ├─ All granted → Continue to Step 6 ⬇️                     │
│  └─ Some denied →                                           │
│     ├─ Permanently denied → Show settings dialog            │
│     └─ Temporarily denied → Show rationale dialog           │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│  STEP 6: FOREGROUND_SERVICE (Android 14+)                  │
│  ├─ Request foreground service permission                   │
│  ├─ Granted → All permissions complete! ✅                  │
│  └─ Denied → Show settings dialog                          │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│  ✅ ALL PERMISSIONS GRANTED → Proceed to Login             │
└─────────────────────────────────────────────────────────────┘
```

## Key Features

### 1. **Sequential Flow**
- Each step waits for the previous one to complete
- User is guided through the process systematically
- No overwhelming permission requests all at once

### 2. **Smart Rationale Handling**
- Shows rationale dialog when user previously denied permissions
- Only shows rationale once per session
- After that, directs user to settings if needed

### 3. **Settings Navigation**
- Context-aware settings dialogs
- Direct navigation to specific settings pages:
  - Location Settings
  - Notification Settings
  - Battery Optimization Settings
  - Battery Saver Settings
  - App Details Settings

### 4. **Graceful Degradation**
- Users can skip battery optimization (continues flow)
- Users can skip battery saver mode (continues flow)
- Critical permissions (location) require explicit grant or settings

### 5. **Platform Compatibility**
- Android 13+ (Tiramisu): POST_NOTIFICATIONS
- Android 10+ (Q): ACCESS_BACKGROUND_LOCATION
- Android 14+ (UPSIDE_DOWN_CAKE): FOREGROUND_SERVICE
- Backward compatible with older versions

## Implementation in LoginFragment

```kotlin
// Initialize permission handler
permissionHandler = PermissionHandler(
    fragment = this,
    onPermissionsGranted = {
        Log.i(TAG, "✅ Permissions granted, proceeding with login")
        doLoginOperations() // Proceed to login
    },
    onPermissionsDenied = { deniedPermissions ->
        Log.i(TAG, "⚠️ Some permissions denied: $deniedPermissions")
        val message = permissionHandler.getDeniedPermissionsMessage(deniedPermissions)
        if (message.isNotEmpty()) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
        }
        // Still allow login even if permissions are denied
        doLoginOperations()
    }
)

// Register the permission launcher
permissionHandler.initializePermissionLauncher(permissionLauncher)

// Start permission flow
mBinding.loginBtn.setOnClickListener {
    permissionHandler.requestPermissions()
}
```

## Permission Result Handling

### For ActivityResultLauncher (Android 13+)
```kotlin
private val permissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions()
) { result ->
    permissionHandler.handlePermissionResult(result)
}
```

### For Legacy onRequestPermissionsResult
```kotlin
override fun onRequestPermissionsResult(
    requestCode: Int, 
    permissions: Array<out String>, 
    grantResults: IntArray
) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    permissionHandler.handlePermissionResult(requestCode, permissions, grantResults)
}
```

## Permission States & Outcomes

### State 1: All Permissions Granted ✅
```
User Experience: Seamless login, no dialogs
Flow: Step 1 → Login
```

### State 2: Some Permissions Denied (First Time) ⚠️
```
User Experience: Rationale dialog shown
Flow: Show rationale → User can grant or cancel
```

### State 3: Permissions Permanently Denied ❌
```
User Experience: Settings dialog shown
Flow: Direct to settings → User must manually enable
```

### State 4: Battery Settings Skipped ⏭️
```
User Experience: Continues to next step
Flow: User clicks "Cancel" → Flow continues
```

## Logging & Debugging

The class provides comprehensive logging at each step:

```
✅ = Success/Completed
⚠️ = Warning/Optional step
❌ = Denied/Failed
⏳ = In progress/Requesting
```

Example log sequence:
```
⏳ Starting comprehensive permission flow
⏳ Requesting POST_NOTIFICATIONS permission
✅ All requested permissions granted via launcher
✅ Battery optimization already disabled
✅ Battery Saver mode is OFF
⏳ Requesting location permissions: [ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION, ACCESS_BACKGROUND_LOCATION]
✅ All requested permissions granted via launcher
✅ All permissions granted! Proceeding...
```

## API Methods

### Public Methods

| Method | Description | Returns |
|--------|-------------|---------|
| `initializePermissionLauncher()` | Register the ActivityResultLauncher | void |
| `requestPermissions()` | Start the permission flow | Boolean |
| `hasAllPermissions()` | Check if all permissions granted | Boolean |
| `hasLocationPermissions()` | Check if location permissions granted | Boolean |
| `handlePermissionResult(Map)` | Handle launcher result | void |
| `handlePermissionResult(Int, Array, IntArray)` | Handle legacy result | void |
| `openAppSettings()` | Open app settings page | void |
| `getDeniedPermissionsMessage()` | Get user-friendly denial message | String |

### Internal Flow Methods

| Method | Purpose |
|--------|---------|
| `checkPostNotificationPermission()` | Step 2: Handle POST_NOTIFICATIONS |
| `checkBatteryOptimization()` | Step 3: Handle battery optimization |
| `checkBatterySaverMode()` | Step 4: Handle battery saver |
| `requestLocationPermissions()` | Step 5: Handle location permissions |
| `checkForegroundServicePermission()` | Step 6: Handle foreground service |
| `onAllPermissionsGranted()` | Final callback when all complete |

## Best Practices

### ✅ DO:
- Initialize handler in `onViewCreated()`
- Register launcher before requesting permissions
- Handle both granted and denied callbacks
- Allow users to continue even if some permissions denied
- Provide clear rationale messages

### ❌ DON'T:
- Request permissions in `onCreate()`
- Show multiple permission dialogs simultaneously
- Block user completely if optional permissions denied
- Request permissions without user action (e.g., button click)

## Testing Checklist

- [ ] Test with all permissions granted
- [ ] Test with all permissions denied
- [ ] Test with some permissions denied
- [ ] Test permanent denial → Settings flow
- [ ] Test battery optimization dialog
- [ ] Test battery saver dialog
- [ ] Test on Android 10 (Q) - Background location
- [ ] Test on Android 13 (Tiramisu) - Notifications
- [ ] Test on Android 14 (UPSIDE_DOWN_CAKE) - Foreground service
- [ ] Test on older versions (API 26-29)
- [ ] Test rationale dialog flow
- [ ] Test settings navigation for each permission type
- [ ] Test "Cancel" on optional steps (battery settings)

## Troubleshooting

### Issue: Permissions not requested
**Solution:** Ensure `initializePermissionLauncher()` is called before `requestPermissions()`

### Issue: Settings dialog doesn't open correct page
**Solution:** Check `showSettingsDialog()` intent for specific permission type

### Issue: Rationale shown repeatedly
**Solution:** `hasShownRationale` flag should reset after permissions granted

### Issue: Flow breaks after battery optimization
**Solution:** Ensure dialogs call next step in both "Settings" and "Cancel" buttons

---

**Last Updated:** December 17, 2025
**Version:** 2.0 (Comprehensive Flow)
**Compatibility:** Android API 26+

