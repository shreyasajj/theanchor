package com.anchor.data.ha

import com.anchor.data.settings.AnchorSettings
import com.google.common.truth.Truth.assertThat
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

class HomeAssistantClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: HomeAssistantClient

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        client = HomeAssistantClient(api = buildApi(server))
    }

    @After
    fun tearDown() = server.shutdown()

    private fun buildApi(server: MockWebServer): HomeAssistantApi {
        val ok = OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .build()
        val json = Json { ignoreUnknownKeys = true }
        return Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(ok)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(HomeAssistantApi::class.java)
    }

    private fun settings() = AnchorSettings(
        haBaseUrl = server.url("/").toString().trimEnd('/'),
        haToken = "llat-secret",
        haDeviceTrackerEntityId = "device_tracker.pixel",
    )

    @Test
    fun `parses state and friendly_name`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"entity_id":"device_tracker.pixel","state":"home",
                 "attributes":{"friendly_name":"Bedroom Presence","source_type":"router"}}
                """.trimIndent()
            )
        )

        val result = client.fetchDeviceTracker(settings())

        assertThat(result).isInstanceOf(HaResult.Ok::class.java)
        val ok = result as HaResult.Ok
        assertThat(ok.state.state).isEqualTo("home")
        assertThat(ok.state.friendlyName).isEqualTo("Bedroom Presence")
    }

    @Test
    fun `sends the bearer token and hits the states endpoint`() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"entity_id":"device_tracker.pixel","state":"home","attributes":{}}""")
        )

        client.fetchDeviceTracker(settings())

        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/api/states/device_tracker.pixel")
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer llat-secret")
    }

    @Test
    fun `a 401 maps to Unavailable, not an exception`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        assertThat(client.fetchDeviceTracker(settings())).isEqualTo(HaResult.Unavailable)
    }

    @Test
    fun `a 500 maps to Unavailable`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        assertThat(client.fetchDeviceTracker(settings())).isEqualTo(HaResult.Unavailable)
    }

    @Test
    fun `malformed json maps to Unavailable`() = runTest {
        server.enqueue(MockResponse().setBody("not json at all"))
        assertThat(client.fetchDeviceTracker(settings())).isEqualTo(HaResult.Unavailable)
    }

    @Test
    fun `a network failure maps to Unavailable`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertThat(client.fetchDeviceTracker(settings())).isEqualTo(HaResult.Unavailable)
    }

    @Test
    fun `unconfigured Home Assistant maps to Unavailable without a request`() = runTest {
        val result = client.fetchDeviceTracker(AnchorSettings())
        assertThat(result).isEqualTo(HaResult.Unavailable)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a trailing slash on the base url does not produce a double slash`() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"entity_id":"device_tracker.pixel","state":"home","attributes":{}}""")
        )

        client.fetchState(
            baseUrl = server.url("/").toString(),   // ends with "/"
            token = "t",
            entityId = "device_tracker.pixel",
        )

        assertThat(server.takeRequest().path).isEqualTo("/api/states/device_tracker.pixel")
    }
}
