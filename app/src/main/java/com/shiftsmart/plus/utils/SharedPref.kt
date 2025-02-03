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