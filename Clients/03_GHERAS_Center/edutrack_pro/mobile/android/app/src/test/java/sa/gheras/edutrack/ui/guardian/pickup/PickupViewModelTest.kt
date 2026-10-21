package sa.gheras.edutrack.ui.guardian.pickup

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import sa.gheras.edutrack.data.db.GherasDatabase
import sa.gheras.edutrack.data.entity.PickupDelegateEntity
import sa.gheras.edutrack.data.entity.RoomEntity
import sa.gheras.edutrack.data.entity.StudentEntity
import sa.gheras.edutrack.data.remote.PickupApi
import sa.gheras.edutrack.data.remote.dto.ActivePickupRequestsResponse
import sa.gheras.edutrack.data.remote.dto.CancelPickupRequest
import sa.gheras.edutrack.data.remote.dto.CreateDelegateRequest
import sa.gheras.edutrack.data.remote.dto.CreatePickupRequest
import sa.gheras.edutrack.data.remote.dto.FailPickupRequest
import sa.gheras.edutrack.data.remote.dto.HandoverRequest
import sa.gheras.edutrack.data.remote.dto.PickupDelegateDto
import sa.gheras.edutrack.data.remote.dto.PickupRequestDto
import sa.gheras.edutrack.data.repo.PickupRepository
import sa.gheras.edutrack.data.repo.StudentsRepository
import sa.gheras.edutrack.sync.Outbox
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Failing-by-default [PickupApi]: the network layer is unavailable in this unit-test batch. */
private class NoNetworkPickupApi : PickupApi {
    override suspend fun getActiveRequests(): ActivePickupRequestsResponse = throw IOException("No network in unit test")
    override suspend fun getRequestById(id: String): PickupRequestDto = throw IOException("No network in unit test")
    override suspend fun createDelegate(body: CreateDelegateRequest): PickupDelegateDto = throw IOException("No network in unit test")
    override suspend fun deleteDelegate(id: String) = throw IOException("No network in unit test")
    override suspend fun createRequest(body: CreatePickupRequest): PickupRequestDto = throw IOException("No network in unit test")
    override suspend fun approveEarlyLeave(id: String): PickupRequestDto = throw IOException("No network in unit test")
    override suspend fun startPrep(id: String): PickupRequestDto = throw IOException("No network in unit test")
    override suspend fun markReady(id: String): PickupRequestDto = throw IOException("No network in unit test")
    override suspend fun handover(id: String, body: HandoverRequest): PickupRequestDto = throw IOException("No network in unit test")
    override suspend fun cancelRequest(id: String, body: CancelPickupRequest): PickupRequestDto = throw IOException("No network in unit test")
    override suspend fun failRequest(id: String, body: FailPickupRequest): PickupRequestDto = throw IOException("No network in unit test")
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PickupViewModelTest {

    private lateinit var db: GherasDatabase
    private lateinit var viewModel: PickupViewModel
    private val mainDispatcher = UnconfinedTestDispatcher()
    private val testIoExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val testIoDispatcher = testIoExecutor.asCoroutineDispatcher()

    private val now: Instant = Instant.parse("2026-10-20T08:00:00Z")

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, GherasDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(testIoExecutor)
            .setTransactionExecutor(testIoExecutor)
            .build()
    }

    @After
    fun tearDown() {
        if (this::viewModel.isInitialized) {
            viewModel.viewModelScope.coroutineContext.cancelChildren()
            viewModel.viewModelScope.cancel()
        }
        db.close()
        testIoExecutor.shutdown()
        Dispatchers.resetMain()
    }

    /**
     * Seeds the FK parents (room → student) plus one existing delegate so the ViewModel's
     * initial collection has both a student row and a delegates list to emit.
     */
    private suspend fun seedStudentAndDelegate() {
        db.roomDao().upsert(
            RoomEntity("room_1", null, "Room 1", "Group 1", now, now)
        )
        db.studentDao().upsert(
            StudentEntity(
                id = "student_1",
                branchId = null,
                name = "Ahmad",
                nationalId = null,
                birthDate = LocalDate.parse("2016-05-01"),
                nationality = "SA",
                difficultyNotes = null,
                childNotes = null,
                fatherName = null,
                fatherPhone = null,
                motherName = null,
                motherPhone = null,
                guardianPhone = null,
                guardianRelation = null,
                pickupType = null,
                pickupName = null,
                pickupRelation = null,
                pickupPhone = null,
                previousSchool = null,
                previousLevel = null,
                educationNotes = null,
                roomId = "room_1",
                groupName = "Group 1",
                createdAt = now,
                updatedAt = now
            )
        )
        db.pickupDao().upsert(
            PickupDelegateEntity(
                id = "delegate_1",
                studentId = "student_1",
                name = "Uncle Khalid",
                phone = "0555555555",
                relation = "Uncle",
                isApproved = true,
                createdAt = now,
                updatedAt = now
            )
        )
    }

    private fun buildViewModel() {
        val outbox = Outbox(
            ApplicationProvider.getApplicationContext<Context>(),
            db
        ) { "teacher_1" }
        val pickupRepository = PickupRepository(
            db.pickupDao(),
            NoNetworkPickupApi(),
            outbox,
            testIoDispatcher
        )
        val studentsRepository = StudentsRepository(db.studentDao())
        viewModel = PickupViewModel(
            studentId = "student_1",
            studentsRepository = studentsRepository,
            pickupRepository = pickupRepository
        )
    }

    @Test
    fun testInitialStateLoadsStudentAndDelegates() = runTest {
        seedStudentAndDelegate()
        buildViewModel()

        viewModel.uiState.test {
            // Wait for the first combined emission where the student row is loaded
            var state = awaitItem()
            while (state.student == null) {
                state = awaitItem()
            }
            assertEquals("Ahmad", state.student?.name)
            assertEquals("student_1", state.student?.id)
            assertEquals("Loaded delegates list must contain the seeded delegate", 1, state.delegates.size)
            assertEquals("Uncle Khalid", state.delegates.first().name)
            assertNull(state.errorMessage)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun testCreateDelegateEnqueuesOutbox() = runTest {
        seedStudentAndDelegate()
        buildViewModel()

        viewModel.addDelegate(name = "Uncle Salem", nationalId = "1098765432", phone = "0551112223", relation = "Uncle")

        // 1. Outbox row enqueued (optimistic offline pattern, network is down).
        // K1/L3: Room's outbox.enqueue runs inside db.withTransaction on Room's transaction
        // executor asynchronously; testIoDispatcher isolates execution and we poll with timeout.
        var outboxCount = 0
        withTimeout(3000) {
            while (outboxCount == 0) {
                db.query("SELECT COUNT(*) FROM pending_writes", null).use { cursor ->
                    if (cursor.moveToFirst()) {
                        outboxCount = cursor.getInt(0)
                    }
                }
                if (outboxCount == 0) delay(20)
            }
        }
        assertEquals("createDelegate must enqueue exactly one outbox row", 1, outboxCount)
        val pending = db.pendingWriteDao().getByNaturalKey("delegate:student_1:1098765432")
        assertNotNull(pending)

        // 2. Optimistic projection applied locally and surfaced through the delegates flow
        viewModel.uiState.test {
            var state = awaitItem()
            while (state.student == null) {
                state = awaitItem()
            }
            // The optimistic projection inserts a local: delegate row for student_1
            var names = state.delegates.map { it.name }
            while (!names.contains("Uncle Salem")) {
                state = awaitItem()
                names = state.delegates.map { it.name }
            }
            assertTrue("Uncle Salem projected into delegates flow", names.contains("Uncle Salem"))

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun testClearError() = runTest {
        seedStudentAndDelegate()
        buildViewModel()

        // Trigger a real failure path (network down) so errorMessage is non-null
        viewModel.requestPickup(isEarlyLeave = false)
        viewModel.uiState.test {
            var state = awaitItem()
            while (state.student == null) {
                state = awaitItem()
            }
            var attempts = 0
            while (state.errorMessage == null && attempts < 50) {
                state = awaitItem()
                attempts++
            }
            assertNotNull("requestPickup against a down network must surface an error", state.errorMessage)
            cancelAndIgnoreRemainingEvents()
        }

        // clearError() resets the error message to null.
        // K1/L3: verify after the state update lands using withTimeout.
        viewModel.clearError()
        withTimeout(3000) {
            while (viewModel.uiState.value.errorMessage != null) {
                delay(20)
            }
        }
        assertNull(viewModel.uiState.value.errorMessage)
    }
}
