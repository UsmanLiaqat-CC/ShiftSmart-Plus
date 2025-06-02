package com.shiftsmart.plus.models
import com.google.gson.annotations.SerializedName

/**
 * Created by Usman Liaqat on 28,Jan,2025
 * usmanliaqat@codecoytechnologies.com,
 * CodeCoy Technologies,
 * Lahore, Pakistan.
 */


data class AttendaceResponseModel(
    @SerializedName("message" )  val message: String,
    @SerializedName("data" )val data: List<AttendanceData>,
    @SerializedName("errors" ) val errors: List<ErrorDetail> ?=null,
)

data class AttendanceData(
    @SerializedName("UUID" ) val UUID: Int,
    @SerializedName("status" ) val status: String,
    @SerializedName("message" ) val message: String,
    @SerializedName("store" ) val store: String,
    @SerializedName("attendanceStatus" ) val attendanceStatus: String,// enum online , offline
)