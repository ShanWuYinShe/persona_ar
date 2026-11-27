package com.persona.ar.data.api

import com.persona.ar.data.preferences.PersonaSettingsCodec
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {
    // 默认针对模拟器使用 10.0.2.2，真机可切换为局域网 IP 例如 http://192.168.1.100:8000/
    var baseUrl: String = "http://10.0.2.2:8000/"
        set(value) {
            val formatted = PersonaSettingsCodec.normalizeServerUrl(value)
            if (field == formatted) return
            field = formatted
            rebuildRetrofit(formatted)
        }

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private var retrofit: Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    @Volatile
    var service: PersonaApiService = retrofit.create(PersonaApiService::class.java)
        private set

    fun createTransientService(targetUrl: String): PersonaApiService {
        val normalized = PersonaSettingsCodec.normalizeServerUrl(targetUrl)
        val probeClient = okHttpClient.newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        return Retrofit.Builder()
            .baseUrl(normalized)
            .client(probeClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PersonaApiService::class.java)
    }

    private fun rebuildRetrofit(newUrl: String) {
        retrofit = Retrofit.Builder()
            .baseUrl(newUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        service = retrofit.create(PersonaApiService::class.java)
    }
}
