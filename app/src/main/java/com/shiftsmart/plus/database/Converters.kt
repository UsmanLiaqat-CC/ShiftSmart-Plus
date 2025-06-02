package com.shiftsmart.plus.database
import androidx.room.ProvidedTypeConverter
import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.shiftsmart.plus.models.WifiModel

/**
 * Created by Usman Liaqat on 28,Jan,2025
 * usmanliaqat@codecoytechnologies.com,
 * CodeCoy Technologies,
 * Lahore, Pakistan.
 */
@ProvidedTypeConverter
class Converters {
    private val gson = Gson()

    @TypeConverter
    fun fromWifiList(wifiList: List<WifiModel>?): String {
        return gson.toJson(wifiList)
    }

    @TypeConverter
    fun toWifiList(wifiListString: String): List<WifiModel>? {
        val type = object : TypeToken<List<WifiModel>>() {}.type
        return gson.fromJson(wifiListString, type)
    }
    @TypeConverter
    fun toRecordmodel(value: String?): RecordModel? {
        val listType = object : TypeToken<RecordModel?>() {}.type
        return Gson().fromJson(value, listType)
    }

    @TypeConverter
    fun fromRecordModel(recordModel: RecordModel?): String? {
        val gson = Gson()
        return gson.toJson(recordModel)
    }

    @TypeConverter
    fun toIssueModel(value: String?): IssueModel? {
        val listType = object : TypeToken<IssueModel?>() {}.type
        return Gson().fromJson(value, listType)
    }

    @TypeConverter
    fun fromIssueModel(issueModel:IssueModel?): String? {
        val gson = Gson()
        return gson.toJson(issueModel)
    }
}