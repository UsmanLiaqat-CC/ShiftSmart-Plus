package com.shiftsmart.plus.models

data class ComplianceReportItem(
    val id: String,
    val title: String,
    val description: String,
    val complianceType: String,
    val isAcknowledged: Boolean,
    val createdAt: Long,      // sentAt — Unix timestamp in ms
    val status: String = "pending",
    val respondedAt: Long? = null,  // null when not yet responded
    val isPendingSync: Boolean = false  // acknowledged locally, not yet confirmed by server
)
