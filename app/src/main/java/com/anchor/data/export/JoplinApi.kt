package com.anchor.data.export

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Url

@Serializable
data class JoplinNoteRequest(
    val title: String,
    val body: String,
)

@Serializable
data class JoplinNoteResponse(
    val id: String = "",
)

/**
 * Joplin's Web Clipper REST API. The token is a query parameter, not a
 * header, so it is baked into the @Url the caller builds.
 */
interface JoplinApi {

    @POST
    suspend fun createNote(
        @Url url: String,
        @Body body: JoplinNoteRequest,
    ): JoplinNoteResponse
}
