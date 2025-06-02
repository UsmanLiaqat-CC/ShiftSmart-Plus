package com.shiftsmart.plus.models

import com.google.gson.annotations.SerializedName

data class RecordsResponseModel(
    @SerializedName("data" ) val data: List<RecordData>,
    @SerializedName("errors" ) val errors: List<ErrorDetail> ?=null,
    @SerializedName("meta" ) val meta: Meta
)

data class RecordData(
    val _id: String,
    val user: String,
    val date: String,
    val coordinates: GeoPoint,
    val store: StoreRecord,
    val inStore: Boolean,
    val wifi_list: List<WifiInfo>,
    val attendanceType: String,
    val attendanceStatus: String,
    val localTime: String,
    val createdAt: String,
    val updatedAt: String,
    val __v: Int
)

data class GeoPoint(
    val type: String,
    val coordinates: List<Double>
)

data class StoreRecord(
    val _id: String,
    val name: String,
    val isActive: Boolean,
    val bssid: List<String>,
    val businessChannel: String,
    val createdAt: String,
    val updatedAt: String,
    val coordinates: Polygon,
    val __v: Int
)

data class Polygon(
    val type: String,
    val coordinates: List<List<List<Double>>>
)

data class WifiInfo(
    val ssid: String,
    val bssid: String,
    val strength: Int,
    val _id: String
)
data class Meta(
    val total: Int,
    val limit: Int,
    val totalPages: Int,
    val currentPage: Int
)