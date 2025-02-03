package com.shiftsmart.plus.models
/**
 * Created by Usman Liaqat on 28,Jan,2025
 * usmanliaqat@codecoytechnologies.com,
 * CodeCoy Technologies,
 * Lahore, Pakistan.
 */



data class DataRequest(
    var UUID: String="",
    val user_id: String,
    val lat: Double,
    val lng: Double,
    val localTime: String, // 24 hours format time
    val time: String, // utc time
    val attendanceType : String,// enum default(for service every 5 mintues) , arrival
    val attendanceStatus : String,// enum online , offline
    val isForceAttendance : Boolean,
    val isLocation : Boolean,
    val wifiService : Boolean,
    val dataService : Boolean,
    val notification : Boolean,
    val batterySaver : Boolean,
    val batteryOptimization : Boolean,
    var wifi_list   : List<WifiModel> = listOf<WifiModel>(),
)

data class WifiModel(
    val ssid: String = "",
    val bssid: String = "",
    val strength:Int
)

