package sa.gheras.edutrack.ui.account

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import sa.gheras.edutrack.data.db.GherasDatabase
import sa.gheras.edutrack.data.entity.PendingWriteEntity
import sa.gheras.edutrack.data.local.session.FakeSessionStore
import sa.gheras.edutrack.data.local.session.Role
import sa.gheras.edutrack.data.local.session.SessionUser
import sa.gheras.edutrack.data.remote.dto.ProfileDto
import sa.gheras.edutrack.data.remote.dto.ProfileScopeDto
import sa.gheras.edutrack.data.remote.dto.ProfileUserDto
import sa.gheras.edutrack.data.remote.fakes.FakeAuthApi
import sa.gheras.edutrack.data.remote.fakes.FakeMeApi
import sa.gheras.edutrack.data.repo.OutboxRepository
import sa.gheras.edutrack.data.repo.SessionRepository
import sa.gheras.edutrack.sync.Outbox
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AccountViewModelTest {

    private lateinit var db: GherasDatabase
    private lateinit var fakeStore: FakeSessionStore
    private lateinit var fakeAuth: FakeAuthApi
    private lateinit var fakeMe: FakeMeApi
    private lateinit var sessionRepo: SessionRepository
    private lateinit var outboxRepo: OutboxRepository
    private lateinit var outbox: Outbox

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, GherasDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        fakeStore = FakeSessionStore()
        fakeStore.user = SessionUser(id = "u1", username = "teacher1", role = Role.TEACHER, name = "معلم غراس")
        fakeStore.profile = ProfileDto(
            user = ProfileUserDto(id = "u1", username = "teacher1", role = "teacher", name = "معلم غراس"),
            scope = ProfileScopeDto(roomIds = emptyList())
        )
        fakeAuth = FakeAuthApi()
        fakeMe = FakeMeApi()
        sessionRepo = SessionRepository(
            store = fakeStore,
            authApi = fakeAuth,
            meApi = fakeMe,
            db = db
        )
        outboxRepo = OutboxRepository(db.pendingWriteDao())
        outbox = Outbox(context, db)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun createVm() = AccountViewModel(
        sessionStore = fakeStore,
        sessionRepository = sessionRepo,
        outboxRepository = outboxRepo,
        outbox = outbox
    )

    @Test
    fun `uiState displays active user and profile`() {
        val vm = createVm()
        val state = vm.uiState.value

        assertNotNull(state.user)
        assertEquals("teacher1", state.user?.username)
        assertEquals("معلم غراس", state.user?.name)
        assertEquals(Role.TEACHER, state.user?.role)
    }

    @Test
    fun `pendingCount observes pending outbox items`() = runBlocking {
        val now = Instant.parse("2026-10-11T08:00:00Z")
        db.pendingWriteDao().upsert(
            PendingWriteEntity(
                id = "pw1",
                kind = PendingWriteEntity.KIND_ATTENDANCE,
                naturalKey = "att_k1",
                payloadJson = "{}",
                createdAt = now
            )
        )

        val vm = createVm()
        val count = vm.pendingCount.first { it == 1 }
        assertEquals(1, count)
    }

    @Test
    fun `logout clears session and invokes callback`() = runBlocking {
        val vm = createVm()
        val deferred = CompletableDeferred<Unit>()

        vm.logout { deferred.complete(Unit) }
        deferred.await()

        assertTrue(vm.uiState.value.isLoggingOut)
        // Store should be cleared
        assertEquals(null, fakeStore.token)
        assertEquals(null, fakeStore.user)
    }

    @Test
    fun `flushAndLogout succeeds and clears session when no network error`() = runBlocking {
        val vm = createVm()
        val deferred = CompletableDeferred<Unit>()

        vm.flushAndLogout(
            onLoggedOut = { deferred.complete(Unit) },
            onFlushFailed = { /* should not fail */ }
        )
        deferred.await()

        assertEquals(null, fakeStore.token)
        assertEquals(null, fakeStore.user)
    }
}
