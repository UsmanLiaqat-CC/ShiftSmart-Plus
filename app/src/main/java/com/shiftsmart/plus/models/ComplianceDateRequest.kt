package com.shiftsmart.plus.models

import com.google.gson.annotations.SerializedName

data class ComplianceDateRequest(
    @SerializedName("startDate") val startDate: String,
    @SerializedName("endDate") val endDate: String
)
