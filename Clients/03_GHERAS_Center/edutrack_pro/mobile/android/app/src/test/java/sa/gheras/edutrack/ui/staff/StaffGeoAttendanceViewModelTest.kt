package sa.gheras.edutrack.ui.staff

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import sa.gheras.edutrack.data.dao.StaffAttendanceDao
import sa.gheras.edutrack.data.entity.StaffAttendanceEntity
import sa.gheras.edutrack.data.entity.StaffShiftEntity
import sa.gheras.edutrack.data.remote.StaffAttendanceApi
import sa.gheras.edutrack.data.remote.dto.StaffAttendanceRecordDto
import sa.gheras.edutrack.data.remote.dto.StaffCheckInRequest
import sa.gheras.edutrack.data.remote.dto.StaffCheckOutRequest
import sa.gheras.edutrack.data.remote.dto.StaffHistoryResponseDto
import sa.gheras.edutrack.data.remote.dto.StaffStatusResponseDto
import sa.gheras.edutrack.data.remote.dto.SupervisorOverrideRequest
import sa.gheras.edutrack.data.repo.StaffAttendanceRepository
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalCoroutinesApi::class)
class StaffGeoAttendanceViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var dao: FakeStaffAttendanceDao
    private lateinit var api: FakeStaffAttendanceApi
    private lateinit var repo: StaffAttendanceRepository
    private val month = LocalDate.now(ZoneId.of("Asia/Riyadh"))
        .format(DateTimeFormatter.ofPattern("yyyy-MM"))

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        dao = FakeStaffAttendanceDao()
        api = FakeStaffAttendanceApi()
        repo = StaffAttendanceRepository(dao, api, dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeStaffAttendanceDao : StaffAttendanceDao {
        val rows = MutableStateFlow<List<StaffAttendanceEntity>>(emptyList())

        override fun getMyAttendanceForMonth(staffId: String, monthPrefix: String): Flow<List<StaffAttendanceEntity>> =
            rows.map { list -> list.filter { it.staffId == staffId && it.date.startsWith(monthPrefix) } }

        override fun getTodayAttendance(staffId: String, date: String): Flow<StaffAttendanceEntity?> =
            rows.map { list -> list.firstOrNull { it.staffId == staffId && it.date == date } }

        override fun getAttendanceByDate(date: String): Flow<List<StaffAttendanceEntity>> =
            rows.map { list -> list.filter { it.date == date } }

        override fun getAttendanceForMonth(monthPrefix: String): Flow<List<StaffAttendanceEntity>> =
            rows.map { list -> list.filter { it.date.startsWith(monthPrefix) } }

        override suspend fun getById(id: String): StaffAttendanceEntity? =
            rows.value.firstOrNull { it.id == id }

        override suspend fun upsert(newRows: List<StaffAttendanceEntity>) {
            val current = rows.value.toMutableList()
            current.removeAll { existing -> newRows.any { it.id == existing.id } }
            current.addAll(newRows)
            rows.value = current
        }

        override suspend fun upsertOne(record: StaffAttendanceEntity) {
            upsert(listOf(record))
        }

        override fun getActiveShifts(): Flow<List<StaffShiftEntity>> =
            MutableStateFlow(emptyList())

        override suspend fun getShiftById(shiftId: String): StaffShiftEntity? = null

        override suspend fun upsertShifts(shifts: List<StaffShiftEntity>) {
            // no-op: shifts are not asserted in these tests
        }
    }

    private class FakeStaffAttendanceApi : StaffAttendanceApi {
        val checkInRequests = mutableListOf<StaffCheckInRequest>()
        val checkOutRequests = mutableListOf<StaffCheckOutRequest>()
        var statusResponse: StaffStatusResponseDto = StaffStatusResponseDto()
        var myStatusError: Throwable? = null
        var historyResponse: StaffHistoryResponseDto = StaffHistoryResponseDto(
            month = "1970-01",
            records = emptyList()
        )

        override suspend fun checkIn(body: StaffCheckInRequest): StaffAttendanceRecordDto {
            checkInRequests += body
            return StaffAttendanceRecordDto(
                id = "att-checkin",
                staffId = "S1",
                date = "2026-01-15",
                status = "حاضر",
                checkInTime = "07:35"
            )
        }

        override suspend fun checkOut(body: StaffCheckOutRequest): StaffAttendanceRecordDto {
            checkOutRequests += body
            return StaffAttendanceRecordDto(
                id = "att-checkout",
                staffId = "S1",
                date = "2026-01-15",
                status = "انصراف",
                checkOutTime = "13:35"
            )
        }

        override suspend fun getMyStatus(): StaffStatusResponseDto {
            myStatusError?.let { throw it }
            return statusResponse
        }

        override suspend fun getMyHistory(month: String?): StaffHistoryResponseDto = historyResponse

        override suspend fun listForSupervisor(
            date: String?,
            month: String?,
            staffId: String?
        ): List<StaffAttendanceRecordDto> = emptyList()

        override suspend fun overrideAttendance(body: SupervisorOverrideRequest): StaffAttendanceRecordDto =
            throw HttpException(Response.error<Unit>(400, "{}".toResponseBody("application/json".toMediaType())))
    }

    private fun createVm() = StaffGeoAttendanceViewModel(repo)

    private fun entity(id: String, staffId: String, date: String) = StaffAttendanceEntity(
        id = id,
        staffId = staffId,
        date = date,
        status = "حاضر",
        createdAt = "2026-01-01T08:00:00Z",
        updatedAt = "2026-01-01T08:00:00Z"
    )

    @Test
    fun `punchCheckIn without location fix blocks and sets error`() {
        val vm = createVm()

        vm.punchCheckIn()

        assertTrue(api.checkInRequests.isEmpty())
        assertNotEquals(null, vm.uiState.value.errorMessage)
    }

    @Test
    fun `punchCheckIn with mock location fixes blocks checkIn`() {
        val vm = createVm()

        vm.updateLocation(lat = 24.7140, lng = 46.6753, accuracy = 10.0, isMock = true)
        vm.punchCheckIn()

        assertTrue(api.checkInRequests.isEmpty())
        assertNotEquals(null, vm.uiState.value.errorMessage)
    }

    @Test
    fun `real fix inside geofence calls checkIn once with coordinates`() {
        val vm = createVm()

        vm.updateLocation(lat = 24.7140, lng = 46.6753, accuracy = 10.0, isMock = false)
        vm.punchCheckIn()

        assertEquals(1, api.checkInRequests.size)
        val request = api.checkInRequests.single()
        assertEquals(24.7140, request.latitude, 0.0)
        assertEquals(46.6753, request.longitude, 0.0)
        assertEquals(10.0, request.accuracy, 0.0)
    }

    @Test
    fun `historyRecords follow server staff_id not user id`() {
        val s1Row = entity(id = "a1", staffId = "S1", date = "${month}-11")
        val otherRow = entity(id = "a2", staffId = "X9", date = "${month}-12")
        dao.rows.value = listOf(s1Row, otherRow)
        api.statusResponse = StaffStatusResponseDto(staffId = "S1", date = "${month}-11")

        val vm = createVm()

        assertEquals(listOf(s1Row), vm.uiState.value.historyRecords)
    }

    @Test
    fun `STAFF_NOT_LINKED surfaces nested server message not HTTP code`() {
        val body = ("{\"error\":{\"code\":\"STAFF_NOT_LINKED\"," +
            "\"message\":\"حسابك غير مرتبط بسجل موظف. يرجى مراجعة الإدارة\"}}")
            .toResponseBody("application/json".toMediaType())
        api.myStatusError = HttpException(Response.error<Unit>(422, body))

        val vm = createVm()

        val state = vm.uiState.value
        assertEquals("حسابك غير مرتبط بسجل موظف. يرجى مراجعة الإدارة", state.errorMessage)
        assertTrue(!state.isLoading)
    }
}
