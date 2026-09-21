package sa.gheras.edutrack.data.repo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import sa.gheras.edutrack.data.db.GherasDatabase
import sa.gheras.edutrack.data.local.session.FakeSessionStore
import sa.gheras.edutrack.data.local.session.Role
import sa.gheras.edutrack.data.remote.fakes.FakeAuthApi
import sa.gheras.edutrack.data.remote.fakes.FakeMeApi

@RunWith(RobolectricTestRunner::class)
class SessionRepositoryTest {

    private lateinit var db: GherasDatabase
    private lateinit var fakeStore: FakeSessionStore
    private lateinit var fakeAuth: FakeAuthApi
    private lateinit var fakeMe: FakeMeApi
    private lateinit var repo: SessionRepository
    private var pullRequested = false

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, GherasDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        fakeStore = FakeSessionStore()
        fakeAuth = FakeAuthApi()
        fakeMe = FakeMeApi()
        pullRequested = false

        repo = SessionRepository(
            store = fakeStore,
            authApi = fakeAuth,
            meApi = fakeMe,
            db = db,
            onFullPullRequested = { pullRequested = true }
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testInitialState_emptyStore_isSignedOut() {
        assertEquals(SessionState.SignedOut, repo.state.value)
    }

    @Test
    fun testLogin_success_setsActiveStateAndRequestsPull() = runTest {
        val result = repo.login("teacher1", "password123")
        assertTrue(result.isSuccess)
        val user = result.getOrThrow()
        assertEquals("teacher1", user.username)
        assertEquals(Role.TEACHER, user.role)

        assertTrue(repo.state.value is SessionState.Active)
        assertEquals("teacher1", (repo.state.value as SessionState.Active).user.username)
        assertTrue("Full pull must be requested after successful login", pullRequested)
    }

    @Test
    fun testLogin_student_success() = runTest {
        fakeAuth.loginUserRole = "student"
        val result = repo.login("1098765432", "password123", Role.STUDENT)
        assertTrue(result.isSuccess)
        val user = result.getOrThrow()
        assertEquals(Role.STUDENT, user.role)
        assertTrue(repo.state.value is SessionState.Active)
        assertEquals(Role.STUDENT, (repo.state.value as SessionState.Active).role)
    }

    @Test
    fun testRoleGate_rejectsManager() = runTest {
        fakeAuth.loginUserRole = "manager"
        val result = repo.login("manager1", "password123")
        assertTrue(result.isFailure)
        assertEquals(SessionState.SignedOut, repo.state.value)
    }

    @Test
    fun testOnUnauthorized_transitionsActiveToExpired() = runTest {
        repo.login("teacher1", "password123")
        assertTrue(repo.state.value is SessionState.Active)

        repo.onUnauthorized()
        assertTrue(repo.state.value is SessionState.Expired)
        assertEquals("teacher1", (repo.state.value as SessionState.Expired).username)
    }

    @Test
    fun testLogout_transitionsToSignedOut() = runTest {
        repo.login("teacher1", "password123")
        repo.logout()
        assertEquals(SessionState.SignedOut, repo.state.value)
    }

    @Test
    fun testDiscardExpiredSession_wipesAndTransitionsToSignedOut() = runTest {
        repo.login("teacher1", "password123")
        repo.onUnauthorized()
        assertTrue(repo.state.value is SessionState.Expired)

        repo.discardExpiredSession()
        assertEquals(SessionState.SignedOut, repo.state.value)
    }
}
