package com.shiftsmart.plus.models

import android.os.Parcel
import android.os.Parcelable

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
    var errorlogs   : List<ErrorModel> = listOf<ErrorModel>(),
)

data class ErrorModel(
    val key: String = "",
    val title: String = "",
    val solution:String="",
    val time: String, // utc time
)

data class WifiModel(
    val ssid: String = "",
    val bssid: String = "",
    val strength:Int
):Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.toString(),
        parcel.toString(),
        parcel.readInt()
    ) {
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(ssid)
        parcel.writeString(bssid)
        parcel.writeInt(strength)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<WifiModel> {
        override fun createFromParcel(parcel: Parcel): WifiModel {
            return WifiModel(parcel)
        }

        override fun newArray(size: Int): Array<WifiModel?> {
            return arrayOfNulls(size)
        }
    }
}

