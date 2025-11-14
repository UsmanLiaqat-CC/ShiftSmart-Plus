# ±1 HOUR BUFFER QUICK REFERENCE

## Rule
**Service starts 1 hour BEFORE shift start, ends 1 hour AFTER shift end**

---

## 📋 Regular Shift Examples

| Shift Start | Shift End | Service Start | Service End | Total Running Time |
|-------------|-----------|---------------|-------------|--------------------|
| 06:00 | 14:00 | **05:00** | **15:00** | 10 hours |
| 07:00 | 15:00 | **06:00** | **16:00** | 10 hours |
| 08:00 | 16:00 | **07:00** | **17:00** | 10 hours |
| 08:00 | 18:00 | **07:00** | **19:00** | 12 hours |
| 09:00 | 17:00 | **08:00** | **18:00** | 10 hours |
| 10:00 | 18:00 | **09:00** | **19:00** | 10 hours |
| 12:00 | 20:00 | **11:00** | **21:00** | 10 hours |

---

## 🌙 Overnight Shift Examples

| Day | Shift Start | Shift End | Service Start | Service End | Notes |
|-----|-------------|-----------|---------------|-------------|-------|
| Mon | 20:00 | Tue 02:00 | **Mon 19:00** | **Tue 03:00** | End extends to next day |
| Mon | 22:00 | Tue 04:00 | **Mon 21:00** | **Tue 05:00** | End extends to next day |
| Mon | 23:00 | Tue 06:00 | **Mon 22:00** | **Tue 07:00** | End extends to next day |
| Tue | 20:00 | Wed 02:00 | **Tue 19:00** | **Wed 03:00** | End extends to next day |
| Fri | 22:00 | Sat 03:00 | **Fri 21:00** | **Sat 04:00** | End extends to next day |
| Sun | 23:30 | Mon 05:30 | **Sun 22:30** | **Mon 06:30** | End extends to next day |

---

## ⏰ Timeline Visualization

### Regular Shift (08:00-18:00)
```
06:00 ────────────────────────────────────────────────────── Outside shift
07:00 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ ← SERVICE STARTS (buffer start)
      ┃ 07:05 → First record
      ┃ 07:10 → Second record
      ┃ 07:15 → Third record
08:00 ┃ ← SHIFT OFFICIALLY STARTS
      ┃ (Service continues running)
      ┃
18:00 ┃ ← SHIFT OFFICIALLY ENDS
      ┃ 18:05 → Continue tracking
      ┃ 18:55 → Last record
19:00 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ ← SERVICE STOPS (buffer end)
20:00 ────────────────────────────────────────────────────── Outside shift
```

### Overnight Shift (Monday 20:00 - Tuesday 02:00)
```
MONDAY
18:00 ────────────────────────────────────────────────────── Outside shift
19:00 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ ← SERVICE STARTS (buffer start)
      ┃ 19:05 → First record
20:00 ┃ ← SHIFT OFFICIALLY STARTS
      ┃ (Service continues)
23:55 ┃ → Last record before midnight
TUESDAY
00:00 ┃ → Day changes, service continues
00:05 ┃ → First record of new day
01:55 ┃ → Continue tracking
02:00 ┃ ← SHIFT OFFICIALLY ENDS
      ┃ 02:05 → Continue tracking (buffer)
      ┃ 02:55 → Last record
03:00 ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ ← SERVICE STOPS (buffer end)
04:00 ────────────────────────────────────────────────────── Outside shift
```

---

## 🔍 How It's Applied in Code

### 1. AlarmScheduler.kt
```kotlin
// For shift 08:00-18:00 on Monday
val startCalendar = getCalendarForShift("Monday", "08:00", -1, false)  
// Result: Monday 07:00 (08:00 - 1 hour)

val endCalendar = getCalendarForShift("Monday", "18:00", +1, false)
// Result: Monday 19:00 (18:00 + 1 hour)
```

### 2. For Overnight Shifts
```kotlin
// For shift Monday 20:00 - Tuesday 02:00
val startCalendar = getCalendarForShift("Monday", "20:00", -1, false)
// Result: Monday 19:00 (20:00 - 1 hour)

val endCalendar = getCalendarForShift("Monday", "02:00", +1, true)
// Result: Tuesday 03:00 (Tuesday 02:00 + 1 hour)
// Note: isEndOfOvernightShift=true adds 1 day then applies +1 hour buffer
```

### 3. ShiftUtils.isTimeWithinBufferRange()
```kotlin
// For shift 08:00-18:00, checking at 07:30
ShiftUtils.isTimeWithinBufferRange(now, "08:00", "18:00")
// Internally creates:
// startCal = 08:00 - 1 hour = 07:00
// endCal   = 18:00 + 1 hour = 19:00
// Checks if now (07:30) is between 07:00 and 19:00 → TRUE
```

---

## 💡 Key Points

1. **Buffer is automatic**: All functions apply ±1 hour automatically
2. **No manual calculation needed**: Just pass shift times, buffer is handled
3. **Overnight detection**: If end hour < start hour → overnight shift
4. **Day transition**: Overnight shifts automatically add 1 day to end time
5. **Consistent across all components**: AlarmReceiver, AlarmScheduler, ShiftStatusWorker

---

## ✅ Where Buffer is Applied

| Component | Function | Buffer Applied |
|-----------|----------|----------------|
| AlarmScheduler | getCalendarForShift() | Yes (via offsetHours param) |
| ShiftUtils | isTimeWithinBufferRange() | Yes (automatically adds ±1 hour) |
| AlarmReceiver | isInsideShiftWindow() | Yes (uses ShiftUtils) |
| ShiftStatusWorker | shouldRunCheck() | Yes (uses ShiftUtils) |
| AttendanceSyncManager | shouldRunCheck() | Yes (uses ShiftUtils) |

---

## 🧪 Testing Commands

### Check if service should run now
Look for these log messages:
```
✅ INSIDE shift window: Monday (08:00 - 18:00 with ±1h buffer)
```

### Verify buffer calculation
```
📍 Checking Today's shift 08:00 → 18:00
   Start: Mon Nov 11 07:00:00 2025  ← Note: 1 hour before shift start
   End:   Mon Nov 11 19:00:00 2025  ← Note: 1 hour after shift end
   Now:   Mon Nov 11 07:30:00 2025
   → ✅ INSIDE
```

---

## 🔧 Troubleshooting

### Service not starting 1 hour before?
Check logs for:
```
⏰ START alarm (shift start - 1h): [time]
```

### Service not stopping 1 hour after?
Check logs for:
```
⏰ STOP alarm (shift end + 1h): [time]
```

### Overnight shift not working?
Look for:
```
🌙 OVERNIGHT shift detected
Adjusted End: [next day date and time]
```

---

## 📌 Remember

**The ±1 hour buffer is NOT configurable - it's a fixed business rule to ensure:**
- Service starts before employee arrives
- Catches early check-ins
- Continues tracking after shift ends
- Catches late check-outs
- Provides grace period for overnight transitions

