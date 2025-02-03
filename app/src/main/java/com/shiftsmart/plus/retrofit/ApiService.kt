package com.shiftsmart.plus.retrofit
import com.shiftsmart.plus.models.LoginRequest
import com.shiftsmart.plus.models.AttendaceResponseModel
import com.shiftsmart.plus.models.DataRequest
import com.shiftsmart.plus.models.UserResponseModel

import retrofit2.Response
import retrofit2.http.*

/**
 * Created by Usman Liaqat on 28,Jan,2025
 * usmanliaqat@codecoytechnologies.com,
 * CodeCoy Technologies,
 * Lahore, Pakistan.
 */
interface ApiService {
    @Headers("Content-Type: application/json")
    @POST("attendance")
    suspend fun sendData(
        @Body dataRequest: List<DataRequest>,
        @Header("Authorization") authToken: String,
    ): Response<AttendaceResponseModel>

    @POST("auth/login")
    suspend fun login(
        @Body loginRequest: LoginRequest
    ): Response<UserResponseModel>

    @GET("user/logout/{id}")
    suspend fun logout(
        @Path("id") id: String,
        @Header("Authorization") authToken: String,
    ): Response<UserResponseModel>
}