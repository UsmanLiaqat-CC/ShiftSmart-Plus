package com.shiftsmart.plus.utils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.shiftsmart.plus.models.AttendaceResponseModel
import retrofit2.Response

fun <T> Response<T>.parseErrorBody(): AttendaceResponseModel? {
    return try {
        val gson = Gson()
        val type = object : TypeToken<AttendaceResponseModel>() {}.type
        gson.fromJson(this.errorBody()?.charStream(), type)
    } catch (e: Exception) {
        null
    }
}
