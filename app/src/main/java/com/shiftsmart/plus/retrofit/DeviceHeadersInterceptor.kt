package com.shiftsmart.plus.retrofit

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import com.shiftsmart.plus.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceHeadersInterceptor @Inject constructor(
    @param:ApplicationContext private val context: Context
) : Interceptor {

    private val deviceId: String by lazy {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("X-Device-Id", toHeaderValue(deviceId))
            .header("X-Device-Name", toHeaderValue(Build.MODEL))
            .header("X-Device-Type", "android")
            .header("X-App-Version", toHeaderValue(BuildConfig.VERSION_NAME))
            .header("X-Network-Type", currentNetworkType())
            .build()
        return chain.proceed(request)
    }

    // Read for each request, because the phone can switch between WiFi and mobile data
    private fun currentNetworkType(): String = try {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
        when {
            capabilities == null -> "none"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    } catch (e: Exception) {
        "unknown"
    }

    // OkHttp throws on non-ASCII header values, which some device names contain
    private fun toHeaderValue(value: String?): String =
        value.orEmpty().filter { it in ' '..'~' }.take(100)
}
