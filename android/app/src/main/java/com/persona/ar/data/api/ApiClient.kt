package com.persona.ar.data.api

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {
    // 默认针对模拟器使用 10.0.2.2，真机可切换为局域网 IP 例如 http://192.168.1.100:8000/
    var baseUrl: String = "http://10.0.2.2:8000/"
        set(value) {
            val formatted = if (value.endsWith("/")) value else "$value/"
            field = formatted
            rebuildRetrofit(formatted)
        }

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private var retrofit: Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    var service: PersonaApiService = retrofit.create(PersonaApiService::class.java)
        private set

    private fun rebuildRetrofit(newUrl: String) {
        retrofit = Retrofit.Builder()
            .baseUrl(newUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        service = retrofit.create(PersonaApiService::class.java)
    }
}
