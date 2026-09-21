package sa.gheras.edutrack.data.remote

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import sa.gheras.edutrack.data.local.session.FakeSessionStore
import sa.gheras.edutrack.data.local.session.Role
import sa.gheras.edutrack.data.local.session.SessionUser

class AuthInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var fakeSessionStore: FakeSessionStore

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        fakeSessionStore = FakeSessionStore()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun testIntercept_addsBearerTokenWhenPresent() {
        fakeSessionStore.saveLogin(
            token = "test_jwt_token",
            user = SessionUser("u1", "teacher1", Role.TEACHER, "Teacher One")
        )

        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(fakeSessionStore))
            .build()

        val request = Request.Builder()
            .url(server.url("/me/profile"))
            .build()

        client.newCall(request).execute()

        val recorded = server.takeRequest()
        assertEquals("Bearer test_jwt_token", recorded.getHeader("Authorization"))
    }

    @Test
    fun testIntercept_on401CallsUnauthorizedCallback() {
        fakeSessionStore.saveLogin(
            token = "expired_token",
            user = SessionUser("u1", "teacher1", Role.TEACHER, "Teacher One")
        )

        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))

        var on401Called = false
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(fakeSessionStore) {
                on401Called = true
            })
            .build()

        val request = Request.Builder()
            .url(server.url("/me/profile"))
            .build()

        client.newCall(request).execute()

        assertTrue("onUnauthorized callback must be triggered on 401", on401Called)
    }
}
