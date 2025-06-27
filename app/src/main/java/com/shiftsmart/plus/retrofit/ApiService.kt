package com.shiftsmart.plus.retrofit
import com.shiftsmart.plus.models.LoginRequest
import com.shiftsmart.plus.models.AttendaceResponseModel
import com.shiftsmart.plus.models.DataRequest
import com.shiftsmart.plus.models.RecordRequest
import com.shiftsmart.plus.models.RecordsResponseModel
import com.shiftsmart.plus.models.TimeSheetModel
import com.shiftsmart.plus.models.UserDetailsResponseModel
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


    @Headers("Content-Type: application/json")
    @POST("attendanceDetail/getUserAttendanceByDateRange/{userId}")
    suspend fun getRecords(
        @Path("userId") userId: String,
        @Query("page") page: Int,
        @Query("limit") limit: Int,
        @Body recordRequest: RecordRequest,
        @Header("Authorization") authToken: String,
    ): Response<RecordsResponseModel>


    @POST("auth/login")
    suspend fun login(
        @Body loginRequest: LoginRequest
    ): Response<UserResponseModel>

    @GET("user/logout/{id}")
    suspend fun logout(
        @Path("id") id: String,
        @Header("Authorization") authToken: String,
    ): Response<UserDetailsResponseModel>

    @GET("user/getUserById/{id}")
    suspend fun getUserById(
        @Path("id") id: String,
        @Header("Authorization") authToken: String,
    ): Response<UserDetailsResponseModel>


    @GET("user/usersTimeSheet/{id}")
    suspend fun timeSheet(
        @Path("id") id: String,
    ): Response<TimeSheetModel>
}