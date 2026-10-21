package sa.gheras.edutrack.data.remote

import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import sa.gheras.edutrack.data.remote.dto.CreateAssignmentRequest
import sa.gheras.edutrack.data.remote.dto.ReviewSubmissionRequest
import sa.gheras.edutrack.data.remote.dto.SubmitAssignmentRequest

class HomeworkApiEndpointTest {

    private lateinit var server: MockWebServer
    private lateinit var api: MeApi

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

        api = retrofit.create(MeApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun testCreateAssignmentModern_postsToAssignmentsPathWithoutMePrefix() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"id":"asgn_1","title":"Quran HW","created_at":"2026-10-21T10:00:00Z","updated_at":"2026-10-21T10:00:00Z"}""")
        )

        val req = CreateAssignmentRequest(
            id = "asgn_1",
            title = "Quran HW",
            subject = "القرآن الكريم",
            dueDate = "2026-10-30",
            studentIds = listOf("std_1"),
            isDraft = true
        )
        api.createAssignmentModern(req)

        val request = server.takeRequest()
        assertEquals("/api/v1/assignments", request.path)
        assertEquals("POST", request.method)
        assertFalse(request.path!!.contains("me/assignments"))
    }

    @Test
    fun testPublishAssignment_hitsAssignmentsPublishPath() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        api.publishAssignment("asgn_123")

        val request = server.takeRequest()
        assertEquals("/api/v1/assignments/asgn_123/publish", request.path)
        assertEquals("POST", request.method)
    }

    @Test
    fun testSubmitAssignment_hitsAssignmentsSubmitPath() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"id":"sub_1","assignment_id":"asgn_1","student_id":"std_1","created_at":"2026-10-21T10:00:00Z","updated_at":"2026-10-21T10:00:00Z"}""")
        )

        val req = SubmitAssignmentRequest(
            studentId = "std_1",
            note = "My solution",
            files = emptyList()
        )
        api.submitAssignment("asgn_1", req)

        val request = server.takeRequest()
        assertEquals("/api/v1/assignments/asgn_1/submit", request.path)
        assertEquals("POST", request.method)
    }

    @Test
    fun testReviewSubmission_hitsSubmissionsReviewPath() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"id":"sub_1","assignment_id":"asgn_1","student_id":"std_1","status":"graded","created_at":"2026-10-21T10:00:00Z","updated_at":"2026-10-21T10:00:00Z"}""")
        )

        val req = ReviewSubmissionRequest(
            status = "graded",
            gradeLevel = "EXCELLENT"
        )
        api.reviewSubmission("sub_1", req)

        val request = server.takeRequest()
        assertEquals("/api/v1/submissions/sub_1/review", request.path)
        assertEquals("POST", request.method)
    }

    @Test
    fun testUploadMedia_hitsUploadsPathWithoutMePrefix() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"url":"/api/v1/me/files/audio.mp3","storage_key":"audio.mp3"}""")
        )

        val body = "fake_audio_bytes".toRequestBody("audio/mpeg".toMediaType())
        api.uploadMedia("audio.mp3", body)

        val request = server.takeRequest()
        assertEquals("/api/v1/uploads", request.path)
        assertEquals("POST", request.method)
        assertEquals("audio.mp3", request.getHeader("X-Filename"))
        assertFalse(request.path!!.contains("me/uploads"))
    }
}
