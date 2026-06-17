package com.shiftsmart.plus.models

data class ComplianceReportItem(
    val id: String,
    val title: String,
    val message: String,
    val complianceType: String,
    val isAcknowledged: Boolean,
    val createdAt: Long  // Unix timestamp in milliseconds
)
