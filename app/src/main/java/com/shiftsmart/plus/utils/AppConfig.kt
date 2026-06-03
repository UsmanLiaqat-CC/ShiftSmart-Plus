package com.shiftsmart.plus.utils

import com.shiftsmart.plus.BuildConfig

object AppConfig {
    val forceComplaintLogin: Boolean = false
    val forceImmediateComplaintAlert: Boolean = false

    val complaintAlertDelayMs: Long
    get() = if (forceImmediateComplaintAlert) 1 * 60 * 1000L else 60 * 60 * 1000L  // 1 min (test) or 1 hour (prod)
}
