package com.shiftsmart.plus.models

import com.google.gson.annotations.SerializedName

/**
 * Created by Usman Liaqat on 30,May,2025
 * usmanliaqat@codecoytechnologies.com,
 * CodeCoy Technologies,
 * Lahore, Pakistan.
 */
class TimeSheetModel(
    val day: String,
    val start: String,
    val end: String,
    val totalHours: Int,
    val attendanceInMin: Int,
    @SerializedName("errors" ) val errors: List<ErrorDetail> ?=null,

    )
