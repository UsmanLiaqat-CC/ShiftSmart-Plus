package com.shiftsmart.plus.viewmodels
import com.shiftsmart.plus.models.LoginRequest
import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shiftsmart.plus.R
import com.shiftsmart.plus.models.AttendaceResponseModel
import com.shiftsmart.plus.models.DataRequest
import com.shiftsmart.plus.models.RecordRequest
import com.shiftsmart.plus.models.RecordsResponseModel
import com.shiftsmart.plus.models.TimeSheetModel
import com.shiftsmart.plus.models.UserDetailsResponseModel
import com.shiftsmart.plus.models.UserModel
import com.shiftsmart.plus.models.UserResponseModel
import com.shiftsmart.plus.repository.MainRepository
import com.shiftsmart.plus.utils.Resource
import com.shiftsmart.plus.utils.SharedPref
import com.shiftsmart.plus.utils.parseErrorBody

import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject

/**
 * Created by Usman Liaqat on 28,Jan,2025
 * usmanliaqat@codecoytechnologies.com,
 * CodeCoy Technologies,
 * Lahore, Pakistan.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: MainRepository,
    @ApplicationContext val application: Context,
):ViewModel()
{
    private  val TAG = "MainViewModel"

    private val _sendDataResponse = MutableLiveData<Resource<AttendaceResponseModel>>()
    val sendDataResponse: LiveData<Resource<AttendaceResponseModel>> get() = _sendDataResponse

    private val _timeSheetResponse = MutableLiveData<Resource<TimeSheetModel>>()
    val timeSheetResponse: LiveData<Resource<TimeSheetModel>> get() = _timeSheetResponse

    private val _attendceRecordResponse = MutableLiveData<Resource<RecordsResponseModel>>()
    val attendceRecordResponse: LiveData<Resource<RecordsResponseModel>> get() = _attendceRecordResponse

    private val _loginResponse = MutableLiveData<Resource<UserResponseModel>>()
    val loginResponse: LiveData<Resource<UserResponseModel>> get() = _loginResponse

    private val _userDetailsResponse = MutableLiveData<Resource<UserModel>>()
    val userDetailsResponse: LiveData<Resource<UserModel>> get() = _userDetailsResponse

    private val _logoutResponse = MutableLiveData<Resource<UserModel>>()
    val logoutResponse: LiveData<Resource<UserModel>> get() = _logoutResponse

    val exceptionHandler = CoroutineExceptionHandler { coroutineContext, throwable ->
        _attendceRecordResponse.value=Resource.Error("${application.getString(R.string.exception_handled)} ${throwable.localizedMessage}")
        _sendDataResponse.value=Resource.Error("${application.getString(R.string.exception_handled)} ${throwable.localizedMessage}")
        _timeSheetResponse.value=Resource.Error("${application.getString(R.string.exception_handled)} ${throwable.localizedMessage}")
        _loginResponse.value=(Resource.Error("${application.getString(R.string.exception_handled)} ${throwable.localizedMessage}"))
        _userDetailsResponse.value=(Resource.Error("${application.getString(R.string.exception_handled)} ${throwable.localizedMessage}"))
        _logoutResponse.value=(Resource.Error("${application.getString(R.string.exception_handled)} ${throwable.localizedMessage}"))
        Log.v("TAG9", application.getString(R.string.exception_handled, throwable.localizedMessage))
    }

    val parentScope by lazy {
        CoroutineScope(Dispatchers.IO  + SupervisorJob() + exceptionHandler)
    }

  /*  fun sendAppData(listDataRequest: List<DataRequest>, token:String, context: Context) {


        _sendDataResponse.value = Resource.Loading(application.getString(R.string.saving_datato_server))

        val token = SharedPref.getInstance(context)?.getToken() ?: ""
        Log.i(TAG, "sendAppData: dataREquest: ${listDataRequest}\nToken:${token}")

        parentScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    repository.sendData(listDataRequest,token)
                }

                withContext(Dispatchers.Main) {

                    if (response.isSuccessful)
                    {
                        val attendaceResponseModel = response.body() as AttendaceResponseModel
                        Log.i(TAG, "sendAppData: response upper success: ${attendaceResponseModel}")

                        if (attendaceResponseModel.errors?.isNotEmpty() == true) {
                            Log.i(TAG, "sendAppData: response inner errors: ${attendaceResponseModel}")

//                            if (attendaceResponseModel.errors[0].code==401||attendaceResponseModel.errors[0].code==422 ||attendaceResponseModel.errors[0].code==500)
//                            {
//                                _sendDataResponse.value = Resource.Error("${application.getString(R.string.unauthorize)} ${attendaceResponseModel.errors[0].code}")
//                            }else{
//                                _sendDataResponse.value = Resource.Error(attendaceResponseModel.errors[0].detail)
//                            }

                            _sendDataResponse.value = Resource.Error("error")

                        }
                        else {
                            Log.i(TAG, "sendAppData: response inner success: ${attendaceResponseModel}")

                            _sendDataResponse.value = Resource.Success(attendaceResponseModel)
                        }
                    }
                    else {
                        val errorResponse = response.parseErrorBody()
                        Log.i(TAG, "sendAppData: response not success: ${errorResponse}-->responseBody:${response.body()}\ndataREquest:${listDataRequest}")
                        if (errorResponse != null && errorResponse.errors?.isNotEmpty() == true) {
//                            if (errorResponse.errors[0].code==401 ||errorResponse.errors[0].code==422 || errorResponse.errors[0].code==500)
//                            {
//                                _sendDataResponse.value = Resource.Error(application.getString(R.string.unauthorize))
//                            }else{
//                                _sendDataResponse.value = Resource.Error(errorResponse.errors[0].detail)
//                            }
                            _sendDataResponse.value = Resource.Error(errorResponse.errors[0].detail)
                        }
                        else {
//                            if (response.code()==401 || response.code()==422 ||response.code()==500)
//                            {
//                                _sendDataResponse.value = Resource.Error(application.getString(R.string.unauthorize))
//                            }else{
//                                _sendDataResponse.value = Resource.Error("Failed with code ${response.code()}: ${response.message()}")
//                            }
                            _sendDataResponse.value = Resource.Error("Failed with code ${response.code()}: ${response.message()}")

                        }
                    }
                }
            } catch (exception: Exception) {
                withContext(Dispatchers.Main) {
                    Log.i(TAG, "sendAppData: response: exception:${exception}")
                    val errorMessage = when (exception) {
                        is IOException -> application.getString(R.string.network_error_please_check_your_internet_connection)
                        is HttpException -> {
                            val code = exception.code()
                            when (code) {
                                500 -> application.getString(R.string.server_error_please_try_again_later)
                                404 -> application.getString(R.string.resource_not_found_please_check_the_url)
                                else -> application.getString(
                                    R.string.http_error,
                                    exception.message()
                                )
                            }
                        }
                        else -> application.getString(
                            R.string.unknown_error,
                            exception.localizedMessage
                        )
                    }
                    _sendDataResponse.value = Resource.Error(errorMessage)
                }
            }
        }

    }
*/

    fun sendAppData(listDataRequest: List<DataRequest>, token: String, context: Context) {
        val authToken = SharedPref.getInstance(context)?.getToken() ?: ""


        _sendDataResponse.postValue(Resource.Loading(application.getString(R.string.saving_datato_server)))

        parentScope.launch {
            try {
                Log.i(TAG, "sendAppData() -> request size: ${listDataRequest.size}, token: $authToken")

                // Run the API call entirely on IO thread
                val response = repository.sendData(listDataRequest, authToken)

                if (response.isSuccessful) {
                    val attendaceResponse = response.body()

                    if (attendaceResponse == null) {
                        _sendDataResponse.postValue(Resource.Error("Empty response from server"))
                        return@launch
                    }

                    if (attendaceResponse.errors?.isNotEmpty() == true) {
                        val firstError = attendaceResponse.errors.first()
                        Log.w(TAG, "Server responded with error: $firstError")
                        _sendDataResponse.postValue(Resource.Error(firstError.detail ?: "Unknown server error"))
                    } else {
                        Log.i(TAG, "sendAppData() success: ${attendaceResponse.data?.size ?: 0} records synced.")
                        _sendDataResponse.postValue(Resource.Success(attendaceResponse))
                    }

                } else {
                    val errorResponse = response.parseErrorBody()
                    val errorMessage = errorResponse?.errors?.firstOrNull()?.detail
                        ?: "Failed with code ${response.code()}: ${response.message()}"

                    Log.e(TAG, "sendAppData() HTTP Error: $errorMessage")
                    _sendDataResponse.postValue(Resource.Error(errorMessage))
                }

            } catch (e: IOException) {
                Log.e(TAG, "Network error", e)
                _sendDataResponse.postValue(
                    Resource.Error(application.getString(R.string.network_error_please_check_your_internet_connection))
                )

            } catch (e: HttpException) {
                val message = when (e.code()) {
                    500 -> application.getString(R.string.server_error_please_try_again_later)
                    404 -> application.getString(R.string.resource_not_found_please_check_the_url)
                    else -> application.getString(R.string.http_error, e.message())
                }
                Log.e(TAG, "HTTP Exception: ${e.code()} ${e.message()}")
                _sendDataResponse.postValue(Resource.Error(message))

            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error", e)
                _sendDataResponse.postValue(
                    Resource.Error(application.getString(R.string.unknown_error, e.localizedMessage))
                )
            }
        }
    }


    fun getTimeSheet(userId: String) {

        _timeSheetResponse.value = Resource.Loading(application.getString(R.string.please_wait))

        parentScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    repository.getTimeSheet(userId)
                }

                Log.i(TAG, "getTimeSheet: response: $response")
                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        val recordResponseModel = response.body()!!
                        Log.i(TAG, "getTimeSheet: success response: $recordResponseModel")
                        _timeSheetResponse.value = Resource.Success(recordResponseModel)
                    } else {
                        val errorResponse = response.parseErrorBody()
                        Log.i(TAG, "getTimeSheet: failed response: $errorResponse --> ${response.body()}")
                        if (errorResponse?.errors?.isNotEmpty() == true) {
                            _timeSheetResponse.value = Resource.Error(errorResponse.errors[0].detail)
                        } else {
                            _timeSheetResponse.value = Resource.Error("Failed with code ${response.code()}: ${response.message()}")
                        }
                    }
                }
            } catch (exception: Exception) {
                withContext(Dispatchers.Main) {
                    Log.i(TAG, "getRecords: exception: $exception")
                    val errorMessage = when (exception) {
                        is IOException -> application.getString(R.string.network_error_please_check_your_internet_connection)
                        is HttpException -> {
                            when (exception.code()) {
                                500 -> application.getString(R.string.server_error_please_try_again_later)
                                404 -> application.getString(R.string.resource_not_found_please_check_the_url)
                                else -> application.getString(R.string.http_error, exception.message())
                            }
                        }
                        else -> application.getString(R.string.unknown_error, exception.localizedMessage)
                    }
                    _timeSheetResponse.value = Resource.Error(errorMessage)
                }
            }
        }
    }
    fun getRecords(startDate: String, token: String, userId: String, page: Int = 1, limit: Int = 10) {
        val request = RecordRequest(startDate,)
        Log.i(TAG, "getRecords: startDate: $startDate\nToken: $token\nRequest: $request")

        _attendceRecordResponse.value = Resource.Loading(application.getString(R.string.please_wait))

        parentScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    repository.getRecords(userId, page, limit, request, token)
                }

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        val recordResponseModel = response.body()!!
                        Log.i(TAG, "getRecords: success response: $recordResponseModel")
                        _attendceRecordResponse.value = Resource.Success(recordResponseModel)
                    } else {
                        val errorResponse = response.parseErrorBody()
                        Log.i(TAG, "getRecords: failed response: $errorResponse --> ${response.body()}")
                        if (errorResponse?.errors?.isNotEmpty() == true) {
                            _attendceRecordResponse.value = Resource.Error(errorResponse.errors[0].detail)
                        } else {
                            _attendceRecordResponse.value = Resource.Error("Failed with code ${response.code()}: ${response.message()}")
                        }
                    }
                }
            } catch (exception: Exception) {
                withContext(Dispatchers.Main) {
                    Log.i(TAG, "getRecords: exception: $exception")
                    val errorMessage = when (exception) {
                        is IOException -> application.getString(R.string.network_error_please_check_your_internet_connection)
                        is HttpException -> {
                            when (exception.code()) {
                                500 -> application.getString(R.string.server_error_please_try_again_later)
                                404 -> application.getString(R.string.resource_not_found_please_check_the_url)
                                else -> application.getString(R.string.http_error, exception.message())
                            }
                        }
                        else -> application.getString(R.string.unknown_error, exception.localizedMessage)
                    }
                    _attendceRecordResponse.value = Resource.Error(errorMessage)
                }
            }
        }
    }


    fun loginUser(loginRequest: LoginRequest) {
        Log.i(TAG, "loginRequest: dataREquest: ${loginRequest}")

        _loginResponse.value=(Resource.Loading(application.getString(R.string.authencting_user)))

        parentScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    repository.loginUser(loginRequest = loginRequest)
                }


                withContext(Dispatchers.Main) {
                    if (response.isSuccessful)
                    {
                        val userResponseModel=response.body()
                        Log.i(TAG, "loginUser: userResponseModel:${userResponseModel}")

                        if (userResponseModel?.errors?.isNotEmpty()==true)
                        {
                            if (userResponseModel.errors[0].code==401 ||userResponseModel.errors[0].code==422)
                            {
                                _loginResponse.value = Resource.Error(application.getString(R.string.unauthorize))
                            }else{
                                _loginResponse.value=(Resource.Error(userResponseModel.errors[0].detail))
                            }
                        }else{
                            Log.i(TAG, "loginUser: Model:${userResponseModel}")
                            _loginResponse.value=(Resource.Success(userResponseModel!!))
                        }
                    } 
                    else {
                        val errorResponse = response.parseErrorBody()

                        Log.i(TAG, "loginUser: not successfull:${errorResponse}")

                        if (errorResponse != null && errorResponse.errors?.isNotEmpty() == true) {

                            if (errorResponse.errors[0].code==401 ||errorResponse.errors[0].code==422)
                            {
                                _loginResponse.value = Resource.Error(application.getString(R.string.unauthorize))
                            }else{
                                _loginResponse.value=(Resource.Error(errorResponse.errors[0].detail))
                            }

                        } else {
                            if (response.code()==401||response.code()==422)
                            {
                                _loginResponse.value = Resource.Error(application.getString(R.string.unauthorize))
                            }else{
                                _loginResponse.value = Resource.Error("Failed with code ${response.code()}: ${response.message()}")
                            }
                            Log.i(TAG, "sendAppData: response not success: ${response.errorBody()?.string()}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.i(TAG, "loginUser: not successfull:Exception occurred: ${e.message}")

                withContext(Dispatchers.Main) {

                    val errorMessage = when (e) {
                        is IOException -> application.getString(R.string.network_error_please_check_your_internet_connection)
                        is HttpException -> {
                            val code = e.code()
                            when (code) {
                                500 -> application.getString(R.string.server_error_please_try_again_later)
                                404 -> application.getString(R.string.resource_not_found_please_check_the_url)
                                else -> application.getString(
                                    R.string.http_error,
                                    e.message()
                                )
                            }
                        }
                        else -> application.getString(
                            R.string.unknown_error,
                            e.localizedMessage
                        )
                    }

                    _loginResponse.value = Resource.Error(errorMessage)
                }
            }
        }

    }

    fun logoutUser(id: String,user_token:String) {
        Log.i(TAG, "logoutuser:: id:${id}\nuser_token:${user_token}")

        _logoutResponse.value=(Resource.Loading(application.getString(R.string.logout_user)))

        parentScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    repository.logout(user_id = id,token=user_token)
                }

                Log.i(TAG, "logoutUser: logoutResponse:${response}");

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful)
                    {
//                        val userResponseModel=response.body() as UserModel
//                        _logoutResponse.value=(Resource.Success(userResponseModel))
//

                        val responseModel = response.body() // UserDetailsResponseModel?
                        val userModel = responseModel?.data // UserModel?

                        if (userModel != null) {
                            Log.i(TAG, "userDetails: success: $userModel")
                            _logoutResponse.value = Resource.Success(userModel)
                        } else {
                            Log.w(TAG, "userDetails: response success but data is null")
                            _logoutResponse.value = Resource.Error("User data is missing.")
                        }
                    } else {

                        val errorResponse = response.parseErrorBody()
                        if (errorResponse != null && errorResponse.errors?.isNotEmpty() == true) {
                            _logoutResponse.value = Resource.Error(errorResponse.errors[0].detail)
                        } else {
                            Log.i(TAG, "sendAppData: response not success: ${response.errorBody()?.string()}")
                            _logoutResponse.value = Resource.Error("Failed with code ${response.code()}: ${response.message()}")
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    val errorMessage = when (e) {
                        is IOException -> application.getString(R.string.network_error_please_check_your_internet_connection)
                        is HttpException -> {
                            val code = e.code()
                            when (code) {
                                500 -> application.getString(R.string.server_error_please_try_again_later)
                                404 -> application.getString(R.string.resource_not_found_please_check_the_url)
                                else -> application.getString(
                                    R.string.http_error,
                                    e.message()
                                )
                            }
                        }
                        else -> application.getString(
                            R.string.unknown_error,
                            e.localizedMessage
                        )
                    }
                    _logoutResponse.value = Resource.Error(errorMessage)
                }
            }
        }

    }

    fun userDetails(id: String,user_token:String) {
        Log.i(TAG, "userDetails:: id:${id}\nuser_token:${user_token}")

        _userDetailsResponse.value=(Resource.Loading(application.getString(R.string.please_wait)))

        parentScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    repository.getUserById(user_id = id,token=user_token)
                }

                withContext(Dispatchers.Main) {

                    if (response.isSuccessful) {
                        val responseModel = response.body() // UserDetailsResponseModel?
                        val userModel = responseModel?.data // UserModel?

                        if (userModel != null) {
                            Log.i(TAG, "userDetails: success: $userModel")
                            _userDetailsResponse.value = Resource.Success(userModel)
                        } else {
                            Log.w(TAG, "userDetails: response success but data is null")
                            _userDetailsResponse.value = Resource.Error("User data is missing.")
                        }
                    } else {
                        val errorResponse = response.parseErrorBody()
                        if (errorResponse != null && errorResponse.errors?.isNotEmpty() == true) {
                            _userDetailsResponse.value = Resource.Error(errorResponse.errors[0].detail)
                        } else {
                            _userDetailsResponse.value = Resource.Error("Failed with code ${response.code()}: ${response.message()}")
                        }
                    }


                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    val errorMessage = when (e) {
                        is IOException -> application.getString(R.string.network_error_please_check_your_internet_connection)
                        is HttpException -> {
                            val code = e.code()
                            when (code) {
                                500 -> application.getString(R.string.server_error_please_try_again_later)
                                404 -> application.getString(R.string.resource_not_found_please_check_the_url)
                                else -> application.getString(
                                    R.string.http_error,
                                    e.message()
                                )
                            }
                        }
                        else -> application.getString(
                            R.string.unknown_error,
                            e.localizedMessage
                        )
                    }
                    _userDetailsResponse.value = Resource.Error(errorMessage)
                }
            }
        }

    }

}