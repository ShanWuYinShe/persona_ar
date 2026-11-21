package com.persona.ar.data.api

import com.persona.ar.data.model.CreateMemoryRequest
import com.persona.ar.data.model.EmotionType
import com.persona.ar.data.model.MemoryItem
import com.persona.ar.data.model.PersonaModelManifest
import com.persona.ar.data.model.PersonaStatus
import com.persona.ar.data.model.TouchRequest
import com.persona.ar.data.model.TouchResponse
import com.persona.ar.data.model.VoiceInteractRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface PersonaApiService {

    @POST("api/v1/chat/interact")
    suspend fun chatInteract(
        @Body request: ChatRequest
    ): Response<ChatResponse>

    @POST("api/v1/voice/interact")
    suspend fun voiceInteract(
        @Body request: VoiceInteractRequest
    ): Response<ChatResponse>

    @POST("api/v1/persona/touch")
    suspend fun personaTouch(
        @Body request: TouchRequest
    ): Response<TouchResponse>

    @GET("api/v1/persona/status")
    suspend fun getPersonaStatus(
        @Query("user_id") userId: String = "user_default"
    ): Response<PersonaStatus>

    @GET("api/v1/store/models")
    suspend fun getStoreModels(): Response<List<PersonaModelManifest>>

    @GET("api/v1/persona/memories")
    suspend fun getMemories(
        @Query("user_id") userId: String = "user_default",
        @Query("persona_id") personaId: String? = null
    ): Response<List<MemoryItem>>

    @POST("api/v1/persona/memories")
    suspend fun createMemory(
        @Body request: CreateMemoryRequest
    ): Response<MemoryItem>

    @DELETE("api/v1/persona/memories/{id}")
    suspend fun deleteMemory(
        @Path("id") id: String,
        @Query("user_id") userId: String = "user_default"
    ): Response<Map<String, Any>>

    @POST("api/v1/persona/switch")
    suspend fun switchPersona(
        @Body request: Map<String, String>
    ): Response<Map<String, Any>>

    @POST("api/v1/persona/reset")
    suspend fun resetPersona(
        @Query("user_id") userId: String = "user_default"
    ): Response<Map<String, String>>
}
