package com.shiftsmart.plus.database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.shiftsmart.plus.database.Converters
import com.shiftsmart.plus.database.DbConstants
import com.shiftsmart.plus.models.WifiModel
import android.os.Build
import com.shiftsmart.plus.BuildConfig

/**
 * Created by Usman Liaqat on 28,Jan,2025
 * usmanliaqat@codecoytechnologies.com,
 * CodeCoy Technologies,
 * Lahore, Pakistan.
 */
@Entity(tableName = DbConstants.RECORD_TABLE_NAME)
@TypeConverters(Converters::class)
data class RecordModel(
    @PrimaryKey
    var uuid: String = "",
    val user_id: String,
    val lat: Double,
    val lng: Double,
    val localTime: String, // 24 hours format time
    val time: String, // utc time
    val attendanceType: String,// enum default(for service every 5 mintues) , arrival
    val attendanceStatus: String,// enum online , offline
    val isForceAttendance: Boolean,  // 5 mintues service false, arrival,departure true
    val isLocation: Boolean,
    val wifiService: Boolean,
    val dataService: Boolean,
    val notification: Boolean,
    val batterySaver: Boolean,
    val batteryOptimization: Boolean,
    var wifi_list: List<WifiModel> = listOf<WifiModel>(),
    val appVersion: String = BuildConfig.VERSION_NAME,
    val deviceName: String = Build.MODEL,
    val deviceType: String = "android",
    val complianceRecordId: String = "",
    val complianceStatus: String = "",
)

