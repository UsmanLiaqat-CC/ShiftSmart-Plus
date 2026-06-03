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

data class UserDetailsResponseModel(
    @SerializedName("data") val data: UserModel?
)

data class UserModel(
    val _id: String,
    val name: String,
    val surName: String,
    val employeeId: String,
    val email: String,
    val idNumber: String,
    val phoneNumber: String,
    val userName: String,
    val password: String,
    val managerID: Manager?,
    val administrator: Manager?,
    val generalManager: Manager?,
    val regionalManager: Manager?,
    val organization: Organization?,
    val businessUnit: List<BusinessUnit>,
    val area: List<Area>,
    val district: List<District>,
    var timetable: TimeTable?,
    var multipleTimeTables: List<MultipleTimeTable>?,
    val store: List<Store>,
    val role: List<Role>,
    var isActive: Boolean,
    val isLogin: Boolean,
    val createdAt: String,
    val updatedAt: String,
    val __v: Int,
    val expectedHours: Int,
    var isComplaint: Boolean,
    val lastSeen: String?,
    val days: List<Day>
)

data class ErrorDetail(
    val title: String,
    val detail: String,
    val code: Int
)



data class Manager(
    val _id: String,
    val name: String,
    val surName: String
)

data class Organization(
    val _id: String,
    val name: String
)

data class BusinessUnit(
    val _id: String,
    val name: String
)

data class Area(
    val _id: String,
    val name: String
)

data class District(
    val _id: String,
    val name: String
)

data class TimeTable(
    val _id: String,
    val name: List<String>,
    val timeTableName: String,
    val isActive: Boolean,
    val range: List<TimeRange>,
    val createdAt: String,
    val updatedAt: String,
    val __v: Int
)
data class MultipleTimeTable(
    val startDate: String,
    val endDate: String,
    val timetable: TimeTable,
    val _id: String
)

data class TimeRange(
    val day: String,
    val start: String,
    val end: String,
    val _id: String
)

data class Store(
    val _id: String,
    val name: String
)

data class Role(
    val _id: String,
    val name: String,
    val permission: List<String>,
    val createdAt: String,
    val updatedAt: String,
    val __v: Int
)

data class Day(
    val day: String,
    val store: List<String>,
    val _id: String
)
