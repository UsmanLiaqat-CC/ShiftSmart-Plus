package com.shiftsmart.plus.repository

import com.shiftsmart.plus.models.LoginRequest
import com.shiftsmart.plus.models.DataRequest
import com.shiftsmart.plus.models.RecordRequest
import com.shiftsmart.plus.retrofit.ApiService
import javax.inject.Inject

/**
 * Created by Usman Liaqat on 28,Jan,2025
 * usmanliaqat@codecoytechnologies.com,
 * CodeCoy Technologies,
 * Lahore, Pakistan.
 */
class MainRepository @Inject constructor(private val retrofitService: ApiService) {
    suspend fun sendData(dataRequest: List<DataRequest>, token:String) = retrofitService.sendData(dataRequest = dataRequest, authToken = "Bearer $token")
    suspend fun getRecords(userId: String, page: Int, limit: Int, recordRequest: RecordRequest, token: String) = retrofitService.getRecords(userId, page, limit, recordRequest, "Bearer $token")
    suspend fun logout(user_id: String,token:String) = retrofitService.logout(id = user_id, authToken = "Bearer $token")
    suspend fun getUserById(user_id: String,token:String) = retrofitService.getUserById(id = user_id, authToken = "Bearer $token")
    suspend fun getTimeSheet(user_id: String) = retrofitService.timeSheet(id = user_id)
    suspend fun loginUser(loginRequest: LoginRequest) = retrofitService.login(loginRequest = loginRequest)
}