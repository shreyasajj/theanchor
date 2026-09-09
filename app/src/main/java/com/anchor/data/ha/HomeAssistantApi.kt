package com.anchor.data.ha

import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Url

/**
 * The base URL is user-configurable and can change at runtime, so every call
 * passes a fully-qualified @Url. Retrofit still needs *some* base URL at
 * construction time; AppModule supplies a placeholder.
 */
interface HomeAssistantApi {

    @GET
    suspend fun state(
        @Url url: String,
        @Header("Authorization") authorization: String,
    ): HaStateDto
}
