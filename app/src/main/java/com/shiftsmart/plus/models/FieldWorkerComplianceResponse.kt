package com.shiftsmart.plus.models

import com.google.gson.annotations.SerializedName

data class FieldWorkerComplianceResponse(
    @SerializedName("data") val data: List<FieldWorkerComplianceItem>,
    @SerializedName("meta") val meta: FieldWorkerMeta
)

data class FieldWorkerComplianceItem(
    @SerializedName("_id") val id: String,
    @SerializedName("status") val status: String,
    @SerializedName("isCompliant") val isCompliant: Boolean,
    @SerializedName("userResponse") val userResponse: String?,
    @SerializedName("respondedAt") val respondedAt: String?,
    @SerializedName("sentAt") val sentAt: String,
    @SerializedName("compliance") val compliance: FieldWorkerCompliance,
    @SerializedName("user") val user: FieldWorkerUser,
    @SerializedName("sender") val sender: FieldWorkerUser
)

data class FieldWorkerCompliance(
    @SerializedName("_id") val id: String,
    @SerializedName("title") val title: String,
    @SerializedName("description") val description: String? = null,
    @SerializedName("complianceType") val complianceType: String
)

data class FieldWorkerUser(
    @SerializedName("_id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("surName") val surName: String
)

data class FieldWorkerMeta(
    @SerializedName("total") val total: Int,
    @SerializedName("limit") val limit: Int,
    @SerializedName("totalPages") val totalPages: Int,
    @SerializedName("currentPage") val currentPage: Int
)
