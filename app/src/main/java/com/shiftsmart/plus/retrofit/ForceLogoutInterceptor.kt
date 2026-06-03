package com.shiftsmart.plus.retrofit

import android.content.Context
import com.shiftsmart.plus.utils.SessionLogoutCoordinator
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ForceLogoutInterceptor @Inject constructor(
    @param:ApplicationContext private val context: Context
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())

        val bodySnapshot = try {
            response.peekBody(1024 * 1024).string()
        } catch (_: Exception) {
            null
        }

        SessionLogoutCoordinator.handleIfSessionExpired(
            context = context,
            httpCode = response.code,
            responseBody = bodySnapshot
        )

        return response
    }
}
