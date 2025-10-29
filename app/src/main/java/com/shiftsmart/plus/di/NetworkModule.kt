package com.shiftsmart.plus.di
import android.app.Service
import android.content.Context
import android.location.LocationManager
import android.net.wifi.WifiManager


import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.shiftsmart.plus.database.DBDao
import com.shiftsmart.plus.database.ShiftSmartPlusDatabase
import com.shiftsmart.plus.repository.MainRepository
import com.shiftsmart.plus.retrofit.ApiService
import com.shiftsmart.plus.services.LocationTrack
import com.shiftsmart.plus.utils.AttendanceSyncManager
import com.shiftsmart.plus.utils.LocationHelper
import com.shiftsmart.plus.utils.WifiScanner
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {


    @JvmStatic
    @Provides
    @Singleton
    fun provideApiInterface(okHttpClient: OkHttpClient , gson: Gson): ApiService {
        return Retrofit.Builder()
            .baseUrl("http://153.92.211.248/tna-phase2-backend/api/v1/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(ApiService::class.java)
    }

    @Provides
    @Singleton
    fun provideApplicationContext(@ApplicationContext context: Context): Context {
        return context
    }
    @get:Provides
    val okHttpClient: OkHttpClient
        get() {

            val okHttpClientBuilder = OkHttpClient.Builder()
            okHttpClientBuilder.readTimeout(100, TimeUnit.SECONDS);
            okHttpClientBuilder.connectTimeout(100, TimeUnit.SECONDS);
            okHttpClientBuilder.writeTimeout(100, TimeUnit.SECONDS);

            return okHttpClientBuilder.build()
        }


    @get:Provides
    val gson: Gson
        get() {
            return  GsonBuilder()
                .setLenient()
                .create()
        }


    @Provides
    @Singleton
    fun provideLocationManager(@ApplicationContext context: Context): LocationManager {
        return context.getSystemService(Service.LOCATION_SERVICE) as LocationManager
    }

    @Provides
    @Singleton
    fun provideLocationTrack(@ApplicationContext context: Context): LocationTrack {
        return LocationTrack(context)
    }

    @Provides
    @Singleton
    fun provideWifiScanner(@ApplicationContext context: Context): WifiScanner {
        return WifiScanner(context)
    }
    @Provides
    fun provideLocationHelper(@ApplicationContext context: Context): LocationHelper {
        return LocationHelper(context)
    }
    @Provides
    @Singleton
    fun provideAttendanceSyncManager(
        context: Context,
        repository: MainRepository,
        dao: DBDao,
    ): AttendanceSyncManager {
        return AttendanceSyncManager(
            context,
            repository,
            dao,
        )
    }
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ShiftSmartPlusDatabase {
        return ShiftSmartPlusDatabase.getInstance(context)
    }

    @Provides
    fun provideDbDao(database: ShiftSmartPlusDatabase): DBDao {
        return database.dbDao()
    }
    @Provides
    @Singleton
    fun provideWifiManager(@ApplicationContext context: Context): WifiManager {
        return context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

}