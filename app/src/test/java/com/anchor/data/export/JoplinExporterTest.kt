package com.anchor.data.export

import com.anchor.data.settings.AnchorSettings
import com.google.common.truth.Truth.assertThat
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

class JoplinExporterTest {

    private lateinit var server: MockWebServer
    private lateinit var exporter: JoplinExporter

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(JoplinApi::class.java)
        exporter = JoplinExporter(api)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun settings() = AnchorSettings(
        joplinBaseUrl = server.url("/").toString().trimEnd('/'),
        joplinToken = "jop-token",
    )

    @Test
    fun `posts the note to slash notes with the token as a query parameter`() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"abc123"}"""))

        val pushed = exporter.push("Daily Anchor - 2026-09-09", "# body", settings())

        assertThat(pushed).isTrue()
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/notes?token=jop-token")
        val sent = request.body.readUtf8()
        assertThat(sent).contains("Daily Anchor - 2026-09-09")
        assertThat(sent).contains("# body")
    }

    @Test
    fun `returns false without a request when Joplin is not configured`() = runTest {
        assertThat(exporter.push("t", "b", AnchorSettings())).isFalse()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `returns false when the token is missing`() = runTest {
        val partial = settings().copy(joplinToken = "")
        assertThat(exporter.push("t", "b", partial)).isFalse()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a 500 returns false instead of throwing`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        assertThat(exporter.push("t", "b", settings())).isFalse()
    }

    @Test
    fun `a network failure returns false instead of throwing`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertThat(exporter.push("t", "b", settings())).isFalse()
    }
}
