package com.shiftsmart.plus.viewmodels
import com.shiftsmart.plus.models.LoginRequest
import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.shiftsmart.plus.R
import com.shiftsmart.plus.models.AttendaceResponseModel
import com.shiftsmart.plus.models.DataRequest
import com.shiftsmart.plus.models.UserResponseModel
import com.shiftsmart.plus.repository.MainRepository
import com.shiftsmart.plus.utils.Resource
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
):ViewModel() {
    private  val TAG = "MainViewModel"
    private val _sendDataResponse = MutableLiveData<Resource<AttendaceResponseModel>>()
    val sendDataResponse: LiveData<Resource<AttendaceResponseModel>> get() = _sendDataResponse


    private val _loginResponse = MutableLiveData<Resource<UserResponseModel>>()
    val loginResponse: LiveData<Resource<UserResponseModel>> get() = _loginResponse

    private val _logoutResponse = MutableLiveData<Resource<UserResponseModel>>()
    val logoutResponse: LiveData<Resource<UserResponseModel>> get() = _logoutResponse

    val exceptionHandler = CoroutineExceptionHandler { coroutineContext, throwable ->
        _sendDataResponse.value=Resource.Error("${application.getString(R.string.exception_handled)} ${throwable.localizedMessage}")
        _loginResponse.value=(Resource.Error("${application.getString(R.string.exception_handled)} ${throwable.localizedMessage}"))
        _logoutResponse.value=(Resource.Error("${application.getString(R.string.exception_handled)} ${throwable.localizedMessage}"))
        Log.v("TAG9", application.getString(R.string.exception_handled, throwable.localizedMessage))
    }

    val parentScope by lazy {
        CoroutineScope(Dispatchers.IO  + SupervisorJob() + exceptionHandler)
    }

    fun sendAppData(listDataRequest: List<DataRequest>, token:String) {

        Log.i(TAG, "sendAppData: dataREquest: ${listDataRequest}\nToken:${token}")

        _sendDataResponse.value = Resource.Loading(application.getString(R.string.saving_datato_server))

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

                            if (attendaceResponseModel.errors[0].code==401||attendaceResponseModel.errors[0].code==422 ||attendaceResponseModel.errors[0].code==500)
                            {
                                _sendDataResponse.value = Resource.Error(application.getString(R.string.unauthorize))
                            }else{
                                _sendDataResponse.value = Resource.Error(attendaceResponseModel.errors[0].detail)
                            }
                        }
                        else {
                            Log.i(TAG, "sendAppData: response inner success: ${attendaceResponseModel}")

                            _sendDataResponse.value = Resource.Success(attendaceResponseModel)
                        }
                    }
                    else {
                        val errorResponse = response.parseErrorBody()
                        Log.i(TAG, "sendAppData: response not success: ${errorResponse}")
                        if (errorResponse != null && errorResponse.errors?.isNotEmpty() == true) {
                            if (errorResponse.errors[0].code==401 ||errorResponse.errors[0].code==422 || errorResponse.errors[0].code==500)
                            {
                                _sendDataResponse.value = Resource.Error(application.getString(R.string.unauthorize))
                            }else{
                                _sendDataResponse.value = Resource.Error(errorResponse.errors[0].detail)
                            }
                        }
                        else {
                            if (response.code()==401 || response.code()==422 ||response.code()==500)
                            {
                                _sendDataResponse.value = Resource.Error(application.getString(R.string.unauthorize))
                            }else{
                                _sendDataResponse.value = Resource.Error("Failed with code ${response.code()}: ${response.message()}")
                            }

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

    fun loginUser(loginRequest: LoginRequest) {
        Log.i(TAG, "loginRequest: dataREquest: ${loginRequest}")

        _loginResponse.value=(Resource.Loading(application.getString(R.string.authencting_user)))

        parentScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    repository.loginUser(loginRequest = loginRequest)
                }

                Log.i(TAG, "loginUser: url:${response}")

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful)
                    {
                        val userResponseModel=response.body()
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

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful)
                    {
                        val userResponseModel=response.body() as UserResponseModel

                        if (userResponseModel.errors?.isNotEmpty()==true)
                        {
                            _logoutResponse.value=(Resource.Error(userResponseModel.errors[0].detail))
                        }else{
                            _logoutResponse.value=(Resource.Success(userResponseModel))
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



}