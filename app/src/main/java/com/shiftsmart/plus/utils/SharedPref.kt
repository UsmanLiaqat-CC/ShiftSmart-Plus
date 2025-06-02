package com.shiftsmart.plus.utils

import android.content.Context
import com.google.gson.Gson
import com.shiftsmart.plus.models.UserModel

/**
 * Created by Usman Liaqat on 28,Jan,2025
 * usmanliaqat@codecoytechnologies.com,
 * CodeCoy Technologies,
 * Lahore, Pakistan.
 */
class SharedPref(private val ctx: Context) {

    private val USER = "user"
    private val TOKEN = "token"
    private val FINGERPRINT = "fingerprint"
    private val LAST_API_CALL_TIME = "last_api_call_time" // <-- New Key for 5-minute check

    // Save Token
    fun saveToken(token: String?) {
        sharedPreferences.edit().apply {
            putString(TOKEN, token)
        }.apply()
    }

    // Get Token
    fun getToken(): String? {
        return sharedPreferences.getString(TOKEN, null)
    }

    fun saveUser(userProfile: UserModel?) {
        val gson = Gson()
        val json = gson.toJson(userProfile)
        sharedPreferences.edit().apply {
            putString(USER, json)
        }.apply()
    }

    fun getUser(): UserModel? {
        val gson = Gson()
        val json: String? = sharedPreferences.getString(USER, null)
        return if (json != null) {
            val obj: UserModel = gson.fromJson(json, UserModel::class.java)
            obj
        } else {
            null
        }

    }
    // ✅ Save Last API Call Time
    fun saveLastApiCallTime(time: Long) {
        sharedPreferences.edit().apply {
            putLong(LAST_API_CALL_TIME, time)
        }.apply()
    }

    // ✅ Get Last API Call Time
    fun getLastApiCallTime(): Long {
        return sharedPreferences.getLong(LAST_API_CALL_TIME, 0L)
    }

    // 🔐 Save fingerprint enable/disable state
    fun setFingerprintEnabled(enabled: Boolean) {
        sharedPreferences.edit().putBoolean(FINGERPRINT, enabled).apply()
    }

    // 🔐 Get fingerprint state
    fun isFingerprintEnabled(): Boolean {
        return sharedPreferences.getBoolean(FINGERPRINT, false)
    }


    fun clearPrefrence() {
        sharedPreferences?.edit()?.clear()?.apply()
    }

    val sharedPreferences = ctx.getSharedPreferences(PREFERENCE, 0)


    companion object {
        private var instance: SharedPref? = null
        var PREFERENCE = "ShiftSmart Plus"

        fun getInstance(context: Context): SharedPref? {
            if (instance == null) {
                instance = SharedPref(context)
            }
            return instance
        }
    }

}