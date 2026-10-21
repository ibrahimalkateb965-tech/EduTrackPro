package sa.gheras.edutrack.ui.supervisor.pickup

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
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
import sa.gheras.edutrack.data.dao.PickupDao
import sa.gheras.edutrack.data.remote.PickupApi
import sa.gheras.edutrack.data.remote.dto.ActivePickupRequestsResponse
import sa.gheras.edutrack.data.remote.dto.CancelPickupRequest
import sa.gheras.edutrack.data.remote.dto.CreateDelegateRequest
import sa.gheras.edutrack.data.remote.dto.CreatePickupRequest
import sa.gheras.edutrack.data.remote.dto.FailPickupRequest
import sa.gheras.edutrack.data.remote.dto.HandoverRequest
import sa.gheras.edutrack.data.remote.dto.PickupDelegateDto
import sa.gheras.edutrack.data.remote.dto.PickupRequestDto
import sa.gheras.edutrack.data.remote.dto.PickupStatus
import sa.gheras.edutrack.data.repo.PickupRepository
import sa.gheras.edutrack.sync.Outbox
import java.io.IOException
import java.time.Instant

/**
 * Controlled [PickupApi] fake: records calls, serves an [ActivePickupRequestsResponse]
 * whose statuses span the full lifecycle, and can be told to fail specific operations.
 */
private class FakeSuperPickupApi(
    var requests: List<PickupRequestDto> = emptyList()
) : PickupApi {

    var getActiveCalls = 0
    var handoverCalls = 0
    var failRequestCalls = 0
    var approveEarlyLeaveCalls = 0
    var failHandover = false
    var failFailRequest = false
    var failApproveEarlyLeave = false

    override suspend fun getActiveRequests(): ActivePickupRequestsResponse {
        getActiveCalls++
        return ActivePickupRequestsResponse(requests)
    }

    override suspend fun getRequestById(id: String): PickupRequestDto = dto(id)

    override suspend fun createDelegate(body: CreateDelegateRequest): PickupDelegateDto = unused()
    override suspend fun deleteDelegate(id: String) = unused()
    override suspend fun createRequest(body: CreatePickupRequest): PickupRequestDto = unused()

    override suspend fun approveEarlyLeave(id: String): PickupRequestDto {
        if (failApproveEarlyLeave) throw IOException("Approve early leave failed")
        approveEarlyLeaveCalls++
        return dto(id)
    }

    override suspend fun startPrep(id: String): PickupRequestDto = unused()
    override suspend fun markReady(id: String): PickupRequestDto = unused()

    override suspend fun handover(id: String, body: HandoverRequest): PickupRequestDto {
        if (failHandover) throw IOException("Handover failed")
        handoverCalls++
        return dto(id)
    }

    override suspend fun cancelRequest(id: String, body: CancelPickupRequest): PickupRequestDto = unused()

    override suspend fun failRequest(id: String, body: FailPickupRequest): PickupRequestDto {
        if (failFailRequest) throw IOException("Fail request failed")
        failRequestCalls++
        return dto(id)
    }

    private fun unused(): Nothing = throw IOException("Not expected in this test batch")

    private fun dto(id: String) = PickupRequestDto(
        id = id,
        branchId = "branch_1",
        studentId = "student_1",
        status = PickupStatus.COMPLETED,
        pickupDate = "2026-10-20",
        pickupTime = "14:00:00"
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SupervisorGateViewModelTest {

    private lateinit var db: GherasDatabase
    private lateinit var api: FakeSuperPickupApi
    private lateinit var viewModel: SupervisorGateViewModel
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, GherasDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        api = FakeSuperPickupApi()
    }

    @After
    fun tearDown() {
        if (this::viewModel.isInitialized) viewModel.viewModelScope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private fun buildViewModel() {
        val outbox = Outbox(
            ApplicationProvider.getApplicationContext<Context>(),
            db
        ) { "supervisor_1" }
        viewModel = SupervisorGateViewModel(PickupRepository(db.pickupDao(), api, outbox, mainDispatcher))
        // The init block's 5s polling loop is parked on the unadvanced test scheduler;
        // cancelling the initializer coroutine frees the suite from the leaked delay.
        viewModel.viewModelScope.coroutineContext.cancelChildren()
    }

    private fun request(id: String, status: String) = PickupRequestDto(
        id = id,
        branchId = "branch_1",
        studentId = "student_1",
        status = status,
        pickupDate = "2026-10-20",
        pickupTime = "14:00:00"
    )

    @Test
    fun testRefreshFiltersActiveStates() = runTest {
        api.requests = listOf(
            request("r1", PickupStatus.NOTIFIED),
            request("r2", PickupStatus.IN_PREP),
            request("r3", PickupStatus.READY),
            request("r4", PickupStatus.COMPLETED),
            request("r5", PickupStatus.CANCELLED)
        )
        buildViewModel()
        viewModel.refresh()

        assertEquals(2, api.getActiveCalls)
        val requests = viewModel.uiState.value.requests
        assertEquals(3, requests.size)
        assertEquals(
            listOf(PickupStatus.IN_PREP, PickupStatus.NOTIFIED, PickupStatus.READY).sorted(),
            requests.map { it.status }.sorted()
        )
        assertTrue(requests.none { it.status == PickupStatus.COMPLETED || it.status == PickupStatus.CANCELLED })
        assertEquals("r1", requests.first { it.status == PickupStatus.NOTIFIED }.id)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun testHandoverCallsRepository() = runTest {
        buildViewModel()

        var successFired = false
        viewModel.handover("r1", "1098765432", "Salem", "0551112223", "Uncle") { successFired = true }

        assertEquals("repository handover must be called once", 1, api.handoverCalls)
        assertEquals(2, api.getActiveCalls)
        assertTrue("onSuccess callback must fire", successFired)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun testReportFailureCallsRepository() = runTest {
        buildViewModel()

        var successFired = false
        viewModel.reportFailure("r2", "Student not found") { successFired = true }

        assertEquals("repository failPickup must be called once", 1, api.failRequestCalls)
        assertEquals(2, api.getActiveCalls)
        assertTrue("onSuccess callback must fire", successFired)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun testApproveEarlyLeaveCallsRepository() = runTest {
        buildViewModel()

        var successFired = false
        viewModel.approveEarlyLeave("r3") { successFired = true }

        assertEquals("repository approveEarlyLeave must be called once", 1, api.approveEarlyLeaveCalls)
        assertEquals(2, api.getActiveCalls)
        assertTrue("onSuccess callback must fire", successFired)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun testClearError() = runTest {
        api.failHandover = true
        buildViewModel()

        viewModel.handover("r1", "1098765432", "Salem", "0551112223", "Uncle")

        val error = viewModel.uiState.value.errorMessage
        assertNotNull("handover failure must set an error message", error)

        viewModel.clearError()
        assertNull("clearError() must reset the error message to null", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun testDecodeServerPickupRequestDtoJsonContract() {
        val json = """
            {
                "id": "req-123",
                "branch_id": "branch-1",
                "student_id": "student-1",
                "student_name": "Ahmad",
                "room_id": "room-1",
                "requested_by_user_id": "user-1",
                "guardian_name": "Abu Ahmad",
                "guardian_phone": "0550001111",
                "guardian_national_id_masked": "******5678",
                "delegate_id": "del-1",
                "delegate_name": "Uncle Salem",
                "delegate_phone": "0551112223",
                "delegate_relation": "Uncle",
                "delegate_national_id_masked": "******1234",
                "status": "notified",
                "pickup_date": "2026-10-20",
                "is_early_leave": false,
                "early_leave_approved": false,
                "client_request_id": "client-uuid-1",
                "notified_at": "2026-10-20T08:00:00Z",
                "created_at": "2026-10-20T08:00:00Z",
                "updated_at": "2026-10-20T08:00:00Z"
            }
        """.trimIndent()
        val format = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val dto = format.decodeFromString(PickupRequestDto.serializer(), json)
        assertEquals("req-123", dto.id)
        assertEquals(PickupStatus.NOTIFIED, dto.status)
        assertNull(dto.pickupTime)
        assertEquals("Uncle Salem", dto.delegateName)
        assertEquals("******1234", dto.delegateNationalIdMasked)
        assertEquals("******5678", dto.guardianNationalIdMasked)
    }
}
