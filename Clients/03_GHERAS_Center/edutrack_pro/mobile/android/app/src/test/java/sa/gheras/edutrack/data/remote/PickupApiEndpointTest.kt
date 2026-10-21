package sa.gheras.edutrack.data.remote

import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import sa.gheras.edutrack.data.remote.dto.CreatePickupRequest
import sa.gheras.edutrack.data.remote.dto.HandoverRequest

class PickupApiEndpointTest {

    private lateinit var server: MockWebServer
    private lateinit var api: PickupApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            encodeDefaults = true
        }
        val contentType = "application/json".toMediaType()

        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/api/v1/"))
            .client(OkHttpClient())
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()

        api = retrofit.create(PickupApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun testActiveRequestsPath_hasSingleApiV1Prefix() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"requests":[]}"""))
        api.getActiveRequests()

        val request = server.takeRequest()
        assertEquals("/api/v1/pickup/requests/active", request.path)
        assertFalse(request.path!!.contains("/api/v1/api/v1/"))
    }

    @Test
    fun testGetRequestByIdPath_hasSingleApiV1Prefix() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"req-123","student_id":"std-1","status":"notified"}"""))
        api.getRequestById("req-123")

        val request = server.takeRequest()
        assertEquals("/api/v1/pickup/requests/req-123", request.path)
        assertFalse(request.path!!.contains("/api/v1/api/v1/"))
    }

    @Test
    fun testCreateRequestPath_hasSingleApiV1Prefix() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"req-123","student_id":"std-1","status":"notified"}"""))
        api.createRequest(CreatePickupRequest(studentId = "std-1"))

        val request = server.takeRequest()
        assertEquals("/api/v1/pickup/requests", request.path)
        assertFalse(request.path!!.contains("/api/v1/api/v1/"))
    }

    @Test
    fun testHandoverPath_hasSingleApiV1Prefix() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"req-123","student_id":"std-1","status":"completed"}"""))
        api.handover(
            "req-123",
            HandoverRequest(
                verifiedReceiverIdNo = "1099988877",
                verifiedReceiverName = "Uncle",
                verifiedReceiverPhone = "0551234567",
                verifiedReceiverRelation = "خال"
            )
        )

        val request = server.takeRequest()
        assertEquals("/api/v1/pickup/requests/req-123/handover", request.path)
        assertFalse(request.path!!.contains("/api/v1/api/v1/"))
    }
}
