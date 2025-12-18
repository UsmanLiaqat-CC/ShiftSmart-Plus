# Fix: Show Dialog Instead of Toast When Permissions Denied

## Problem

When a user denied permissions and then clicked on Arrival, Departure, or Sync buttons, the app would:
1. Check if permissions are granted
2. Find they are not granted
3. Call `permissionHandler.requestPermissions()`
4. **BUG**: If permissions were permanently denied (user selected "Don't ask again"), the `onPermissionsDenied()` callback would just show a simple toast/snackbar message
5. User had no way to grant permissions - stuck in a loop

### User Experience Before Fix

```
User clicks "Arrival" button
         ↓
Check permissions → Not granted
         ↓
Request permissions
         ↓
User denies (or already permanently denied)
         ↓
onPermissionsDenied() called
         ↓
Show toast: "Location permission is recommended..."
         ↓
❌ User stuck - no way to grant permissions
         ↓
User clicks "Arrival" again
         ↓
Same toast appears again ❌
```

---

## Solution

Updated `onPermissionsDenied()` to show **proper AlertDialog** with actionable options:

1. **If permanently denied**: Show dialog to open App Settings
2. **If temporarily denied**: Show dialog to try requesting again

### User Experience After Fix

#### Scenario 1: Temporarily Denied (Can request again)

```
User clicks "Arrival" button
         ↓
Check permissions → Not granted
         ↓
Request permissions
         ↓
User denies
         ↓
onPermissionsDenied() called
         ↓
Check: shouldShowRequestPermissionRationale() = true
         ↓
Show AlertDialog:
  Title: "Permissions Required"
  Message: "Location permission is recommended...
            Would you like to grant them now?"
  Buttons: [Try Again] [Cancel]
         ↓
User clicks "Try Again"
         ↓
✅ Request permissions again
         ↓
User grants
         ↓
✅ Action executes automatically
```

#### Scenario 2: Permanently Denied (Need to go to settings)

```
User clicks "Sync" button
         ↓
Check permissions → Not granted
         ↓
Request permissions
         ↓
System shows: "Don't ask again" dialog (user previously denied)
         ↓
User clicks "Don't allow"
         ↓
onPermissionsDenied() called
         ↓
Check: shouldShowRequestPermissionRationale() = false
         ↓
Show AlertDialog:
  Title: "Permissions Required"
  Message: "Location permission is recommended...
            Please enable them in app settings to use this feature."
  Buttons: [Go to Settings] [Cancel]
         ↓
User clicks "Go to Settings"
         ↓
✅ Opens App Settings page
         ↓
User grants permissions
         ↓
User returns to app
         ↓
✅ pendingAction still stored, will execute when page resumes
```

---

## Code Changes

### 1. Updated `onPermissionsDenied()` Method

**Before (Just showed toast):**
```kotlin
private fun onPermissionsDenied(deniedPermissions: List<String>) {
    Log.i(TAG, "❌ Permissions denied: $deniedPermissions")
    pendingAction = null // Clear pending action
    val message = permissionHandler.getDeniedPermissionsMessage(deniedPermissions)
    if (message.isNotEmpty()) {
        Utils.showSnackBar(message, mBinding.root)  // ❌ Just a toast!
    }
}
```

**After (Shows actionable dialog):**
```kotlin
private fun onPermissionsDenied(deniedPermissions: List<String>) {
    Log.i(TAG, "❌ Permissions denied: $deniedPermissions")
    
    // Check if any permission is permanently denied
    val permanentlyDenied = deniedPermissions.any { permission ->
        !ActivityCompat.shouldShowRequestPermissionRationale(requireActivity(), permission)
    }
    
    if (permanentlyDenied) {
        // Show dialog to open app settings
        showPermissionSettingsDialog(deniedPermissions)
    } else {
        // Show dialog to try again
        showPermissionRetryDialog(deniedPermissions)
    }
}
```

### 2. Added `showPermissionSettingsDialog()` Method

Shows dialog when permissions are permanently denied:

```kotlin
private fun showPermissionSettingsDialog(deniedPermissions: List<String>) {
    val message = permissionHandler.getDeniedPermissionsMessage(deniedPermissions)
    
    AlertDialog.Builder(requireContext())
        .setTitle(getString(R.string.permissions_required))
        .setMessage("$message\n\nPlease enable them in app settings to use this feature.")
        .setPositiveButton(getString(R.string.go_to_settings)) { _, _ ->
            // Open app settings
            permissionHandler.openAppSettings()
            // Keep pending action so it can execute after user returns
        }
        .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
            pendingAction = null // Clear pending action on cancel
            dialog.dismiss()
        }
        .setCancelable(false)
        .show()
}
```

### 3. Added `showPermissionRetryDialog()` Method

Shows dialog when permissions are temporarily denied:

```kotlin
private fun showPermissionRetryDialog(deniedPermissions: List<String>) {
    val message = permissionHandler.getDeniedPermissionsMessage(deniedPermissions)
    
    AlertDialog.Builder(requireContext())
        .setTitle(getString(R.string.permissions_required))
        .setMessage("$message\n\nWould you like to grant them now?")
        .setPositiveButton(getString(R.string.try_again)) { _, _ ->
            // Request permissions again
            permissionHandler.requestPermissions()
        }
        .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
            pendingAction = null // Clear pending action on cancel
            dialog.dismiss()
        }
        .setCancelable(false)
        .show()
}
```

### 4. Added New String Resources

Added to `strings.xml`:
```xml
<string name="permissions_required">Permissions Required</string>
<string name="try_again">Try Again</string>
```

Existing strings used:
- `R.string.go_to_settings` (already existed)
- `R.string.cancel` (already existed)

---

## Flow Diagrams

### Permanently Denied Flow

```
┌─────────────────────┐
│ User Clicks Button  │
│ (Arrival/Departure) │
└──────────┬──────────┘
           │
           ▼
    ┌──────────────────┐
    │ hasAllPermissions│
    │ = false          │
    └──────────┬───────┘
               │
               ▼
    ┌──────────────────────┐
    │ Store pendingAction  │
    └──────────┬───────────┘
               │
               ▼
    ┌──────────────────────┐
    │ Request Permissions  │
    └──────────┬───────────┘
               │
               ▼
    ┌─────────────────────────────┐
    │ User Previously Selected    │
    │ "Don't ask again"           │
    └──────────┬──────────────────┘
               │
               ▼
    ┌──────────────────────┐
    │ onPermissionsDenied()│
    └──────────┬───────────┘
               │
               ▼
    ┌──────────────────────────────┐
    │ Check:                       │
    │ shouldShowRationale = false  │
    │ → Permanently Denied         │
    └──────────┬───────────────────┘
               │
               ▼
    ┌──────────────────────────────┐
    │ Show AlertDialog:            │
    │ "Permissions Required"       │
    │                              │
    │ Message: "Please enable in   │
    │          app settings..."    │
    │                              │
    │ [Go to Settings] [Cancel]    │
    └──────────┬───────────────────┘
               │
               ├─── User clicks "Go to Settings" ───┐
               │                                     │
               │                                     ▼
               │                      ┌──────────────────────┐
               │                      │ Open App Settings    │
               │                      └──────────┬───────────┘
               │                                 │
               │                                 ▼
               │                      ┌──────────────────────┐
               │                      │ User Grants Perms    │
               │                      └──────────┬───────────┘
               │                                 │
               │                                 ▼
               │                      ┌──────────────────────┐
               │                      │ User Returns to App  │
               │                      └──────────┬───────────┘
               │                                 │
               │                                 ▼
               │                      ┌──────────────────────┐
               │                      │ onResume() / checks  │
               │                      │ pendingAction exists │
               │                      └──────────┬───────────┘
               │                                 │
               │                                 ▼
               │                      ┌──────────────────────┐
               │                      │ ✅ Execute Action    │
               │                      └──────────────────────┘
               │
               └─── User clicks "Cancel" ───┐
                                            │
                                            ▼
                              ┌──────────────────────┐
                              │ Clear pendingAction  │
                              │ Dismiss Dialog       │
                              └──────────────────────┘
```

### Temporarily Denied Flow

```
┌─────────────────────┐
│ User Clicks Button  │
└──────────┬──────────┘
           │
           ▼
    ┌──────────────────┐
    │ hasAllPermissions│
    │ = false          │
    └──────────┬───────┘
               │
               ▼
    ┌──────────────────────┐
    │ Store pendingAction  │
    └──────────┬───────────┘
               │
               ▼
    ┌──────────────────────┐
    │ Request Permissions  │
    └──────────┬───────────┘
               │
               ▼
    ┌─────────────────────┐
    │ User Denies         │
    │ (First Time)        │
    └──────────┬──────────┘
               │
               ▼
    ┌──────────────────────┐
    │ onPermissionsDenied()│
    └──────────┬───────────┘
               │
               ▼
    ┌──────────────────────────────┐
    │ Check:                       │
    │ shouldShowRationale = true   │
    │ → Temporarily Denied         │
    └──────────┬───────────────────┘
               │
               ▼
    ┌──────────────────────────────┐
    │ Show AlertDialog:            │
    │ "Permissions Required"       │
    │                              │
    │ Message: "Would you like to  │
    │          grant them now?"    │
    │                              │
    │ [Try Again] [Cancel]         │
    └──────────┬───────────────────┘
               │
               ├─── User clicks "Try Again" ───┐
               │                                │
               │                                ▼
               │                  ┌──────────────────────────┐
               │                  │ Request Permissions Again│
               │                  └──────────┬───────────────┘
               │                             │
               │                             ▼
               │                  ┌──────────────────────┐
               │                  │ User Grants Perms    │
               │                  └──────────┬───────────┘
               │                             │
               │                             ▼
               │                  ┌──────────────────────┐
               │                  │ onPermissionsGranted │
               │                  └──────────┬───────────┘
               │                             │
               │                             ▼
               │                  ┌──────────────────────┐
               │                  │ ✅ Execute Action    │
               │                  └──────────────────────┘
               │
               └─── User clicks "Cancel" ───┐
                                            │
                                            ▼
                              ┌──────────────────────┐
                              │ Clear pendingAction  │
                              │ Dismiss Dialog       │
                              └──────────────────────┘
```

---

## Key Improvements

### Before ❌
- Just showed a toast message
- No actionable options for user
- User stuck in a loop
- Permissions could not be granted
- Poor user experience

### After ✅
- Shows proper AlertDialog
- **Temporarily Denied**: "Try Again" button re-requests permissions
- **Permanently Denied**: "Go to Settings" button opens app settings
- "Cancel" option to dismiss
- Pending action preserved when going to settings
- Clear user guidance
- Professional UX

---

## Testing Scenarios

### Test 1: First Time Denial (Temporary)
1. Fresh install
2. Login and navigate to Home
3. Deny all permissions
4. Click "Arrival" button
5. **Expected**: Dialog appears with "Try Again" button
6. Click "Try Again"
7. **Expected**: Permission request appears again
8. Grant permissions
9. **Expected**: Arrival action executes automatically ✅

### Test 2: Permanent Denial
1. Fresh install
2. Login and navigate to Home
3. Deny permissions
4. Click "Arrival" button
5. Deny again and select "Don't ask again"
6. Click "Arrival" button again
7. **Expected**: Dialog appears with "Go to Settings" button
8. Click "Go to Settings"
9. **Expected**: App Settings page opens ✅
10. Grant location permissions
11. Return to app
12. **Expected**: Can now use Arrival button ✅

### Test 3: Cancel Dialog
1. Click button without permissions
2. Deny permissions
3. Dialog appears
4. Click "Cancel"
5. **Expected**: Dialog dismisses, pending action cleared ✅
6. Click button again
7. **Expected**: New permission request flow starts ✅

### Test 4: Multiple Buttons
1. Click "Sync" without permissions
2. Deny permissions
3. Dialog appears with "Try Again"
4. Click "Try Again"
5. Grant permissions
6. **Expected**: Sync action executes ✅
7. Click "Departure" (permissions already granted)
8. **Expected**: Departure action executes immediately ✅

---

## Benefits

1. ✅ **Better UX**: Clear actionable dialogs instead of passive toasts
2. ✅ **User Guidance**: Explains exactly what user needs to do
3. ✅ **Settings Integration**: Direct link to app settings when needed
4. ✅ **Retry Mechanism**: "Try Again" for temporary denials
5. ✅ **Preserved State**: Pending action kept when opening settings
6. ✅ **Professional**: Follows Android best practices
7. ✅ **Clear Messages**: Uses existing `getDeniedPermissionsMessage()` from PermissionHandler
8. ✅ **No More Stuck State**: User always has a path forward

---

## Implementation Notes

### Permission Detection Logic

```kotlin
val permanentlyDenied = deniedPermissions.any { permission ->
    !ActivityCompat.shouldShowRequestPermissionRationale(requireActivity(), permission)
}
```

This checks if `shouldShowRequestPermissionRationale()` returns `false`, which means:
- User previously denied and selected "Don't ask again", OR
- First time asking (before any interaction), OR
- Permission is restricted by policy

For our use case, if it returns `false` after a denial, we treat it as permanent and direct user to settings.

### Pending Action Preservation

- When showing settings dialog: **pendingAction is kept** (not cleared)
- When showing retry dialog: **pendingAction is kept** (not cleared)
- When user clicks "Cancel": **pendingAction is cleared**
- When permissions granted: **pendingAction executes and is cleared**

This ensures that if user goes to settings and grants permissions, their original action (arrival/departure/sync) will still execute when they return.

---

## Summary

The fix transforms the permission denial experience from a dead-end toast message into an interactive flow that guides users to grant permissions through:
- **Smart detection** of permanent vs temporary denials
- **Actionable dialogs** with "Try Again" or "Go to Settings"
- **Preserved state** so actions execute after granting permissions
- **Clear messaging** explaining what's needed and why

Users are no longer stuck when permissions are denied - they always have a clear path forward! ✅

