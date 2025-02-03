package com.shiftsmart.plus.models

import com.google.gson.annotations.SerializedName


data class UserResponseModel(
    @SerializedName("data") val data: Data?,

    @SerializedName("errors" ) val errors: List<ErrorDetail> ?=null,

)

data class Data(
    @SerializedName("user") val userModel: UserModel?,
    @SerializedName("accessToken") val accessToken: String?,
)

data class UserModel(
    @SerializedName("_id") val id: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("surName") val surName: String?,
    @SerializedName("employeeId") val employeeId: String?,
    @SerializedName("email") val email: String?,
    @SerializedName("idNumber") val idNumber: String?,
    @SerializedName("phoneNumber") val phoneNumber: String?,
    @SerializedName("userName") val userName: String?,
    @SerializedName("password") val password: String?,
    @SerializedName("managerID") val managerID: Person?,
    @SerializedName("administrator") val administrator: Person?,
    @SerializedName("generalManager") val generalManager: Person?,
    @SerializedName("regionalManager") val regionalManager: Person?,
    @SerializedName("organization") val organization: Entity?,
    @SerializedName("businessUnit") val businessUnit: Entity?,
    @SerializedName("area") val area: List<Entity>?,
    @SerializedName("district") val district: List<Entity>?,
    @SerializedName("timetable") val timetable: Timetable?,
    @SerializedName("store") val store: List<Entity>?,
    @SerializedName("role") val role: List<Role>?,
    @SerializedName("isActive") val isActive: Boolean?,
    @SerializedName("isLogin") val isLogin: Boolean?,
    @SerializedName("createdAt") val createdAt: String?,
    @SerializedName("updatedAt") val updatedAt: String?,
    @SerializedName("expectedHours") val expectedHours: Int?,
    @SerializedName("isComplaint") val isComplaint: Boolean?,
    @SerializedName("lastSeen") val lastSeen: String?
)

data class Person(
    @SerializedName("_id") val id: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("surName") val surName: String?
)

data class Entity(
    @SerializedName("_id") val id: String?,
    @SerializedName("name") val name: String?
)

data class Timetable(
    @SerializedName("_id") val id: String?,
    @SerializedName("name") val name: List<String>?,
    @SerializedName("timeTableName") val timeTableName: String?,
    @SerializedName("isActive") val isActive: Boolean?,
    @SerializedName("range") val range: List<TimeRange>?,
    @SerializedName("createdAt") val createdAt: String?,
    @SerializedName("updatedAt") val updatedAt: String?
)

data class TimeRange(
    @SerializedName("day") val day: String?,
    @SerializedName("start") val start: String?,
    @SerializedName("end") val end: String?,
    @SerializedName("_id") val id: String?
)

data class Role(
    @SerializedName("_id") val id: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("permission") val permission: List<String>?,
    @SerializedName("createdAt") val createdAt: String?,
    @SerializedName("updatedAt") val updatedAt: String?
)

data class ErrorDetail(
    val title: String,
    val detail: String,
    val code: Int
)