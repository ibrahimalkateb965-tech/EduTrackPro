package sa.gheras.edutrack.ui.supervisor.attendance

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import sa.gheras.edutrack.data.db.GherasDatabase
import sa.gheras.edutrack.data.remote.dto.AttendanceExcuseDto
import sa.gheras.edutrack.data.remote.dto.ReviewExcuseRequest
import sa.gheras.edutrack.data.remote.dto.StaffExcusesResponse
import sa.gheras.edutrack.data.remote.fakes.FakeMeApi
import sa.gheras.edutrack.data.repo.AttendanceRepository
import sa.gheras.edutrack.sync.Outbox

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SupervisorExcusesViewModelTest {

    private lateinit var db: GherasDatabase
    private lateinit var fakeApi: TestSupervisorMeApi
    private lateinit var attendanceRepo: AttendanceRepository

    class TestSupervisorMeApi : FakeMeApi() {
        val excusesList = mutableListOf<AttendanceExcuseDto>()
        var lastReviewAction: String? = null
        var lastReviewNotes: String? = null
        var shouldThrowConflict = false

        override suspend fun listExcusesStaff(
            status: String?,
            roomId: String?,
            dateFrom: String?,
            dateTo: String?,
            studentId: String?
        ): StaffExcusesResponse {
            val filtered = if (status == null) excusesList else excusesList.filter { it.status == status }
            return StaffExcusesResponse(filtered)
        }

        override suspend fun reviewExcuse(id: String, body: ReviewExcuseRequest): AttendanceExcuseDto {
            if (shouldThrowConflict) {
                val errorBody = okhttp3.ResponseBody.create(okhttp3.MediaType.parse("application/json"), "{\"detail\":\"Conflict\"}")
                throw retrofit2.HttpException(retrofit2.Response.error<Any>(409, errorBody))
            }
            lastReviewAction = body.action
            lastReviewNotes = body.supervisorNotes
            val index = excusesList.indexOfFirst { it.id == id }
            val current = excusesList[index]
            val newStatus = when (body.action) {
                "approve" -> "approved"
                "reject" -> "rejected"
                else -> "needs_info"
            }
            val updated = current.copy(
                status = newStatus,
                supervisorNotes = body.supervisorNotes
            )
            excusesList[index] = updated
            return updated
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, GherasDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val outbox = Outbox(context, db) { "supervisor_1" }
        fakeApi = TestSupervisorMeApi()
        attendanceRepo = AttendanceRepository(db.studentAttendanceDao(), db.attendanceExcuseDao(), fakeApi, outbox)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun sampleExcuse(
        id: String,
        studentName: String = "عبدالله أحمد",
        status: String = "pending"
    ) = AttendanceExcuseDto(
        id = id,
        studentId = "s1",
        studentName = studentName,
        roomId = "r1",
        roomName = "فصل البراعم",
        excuseDate = "2026-10-22",
        reasonText = "وعكة صحية طارئة",
        status = status
    )

    @Test
    fun `loads excuses on init with default pending filter`() = runBlocking {
        fakeApi.excusesList.addAll(
            listOf(
                sampleExcuse("e1", "طالب 1", "pending"),
                sampleExcuse("e2", "طالب 2", "approved"),
                sampleExcuse("e3", "طالب 3", "pending")
            )
        )

        val vm = SupervisorExcusesViewModel(attendanceRepo)
        val state = vm.uiState.value

        assertEquals("pending", state.selectedFilter)
        assertEquals(2, state.excuses.size)
        assertTrue(state.excuses.all { it.status == "pending" })
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
    }

    @Test
    fun `changing filter refreshes excuses list with selected status`() = runBlocking {
        fakeApi.excusesList.addAll(
            listOf(
                sampleExcuse("e1", "طالب 1", "pending"),
                sampleExcuse("e2", "طالب 2", "approved"),
                sampleExcuse("e3", "طالب 3", "rejected"),
                sampleExcuse("e4", "طالب 4", "needs_info")
            )
        )

        val vm = SupervisorExcusesViewModel(attendanceRepo)

        vm.setFilter("approved")
        assertEquals(1, vm.uiState.value.excuses.size)
        assertEquals("approved", vm.uiState.value.excuses.first().status)

        vm.setFilter(null) // All
        assertEquals(4, vm.uiState.value.excuses.size)
    }

    @Test
    fun `reviewExcuse approve succeeds without mandatory notes and refreshes list`() = runBlocking {
        fakeApi.excusesList.add(sampleExcuse("e1", "طالب 1", "pending"))
        val vm = SupervisorExcusesViewModel(attendanceRepo)

        var successCallbackTriggered = false
        vm.reviewExcuse(
            excuseId = "e1",
            action = "approve",
            notes = null,
            onSuccess = { successCallbackTriggered = true }
        )

        assertTrue(successCallbackTriggered)
        assertEquals("approve", fakeApi.lastReviewAction)
        assertNull(fakeApi.lastReviewNotes)
        assertNotNull(vm.uiState.value.successMessage)
        // Since filter is "pending", the approved excuse will be removed from the pending list
        assertTrue(vm.uiState.value.excuses.isEmpty())
    }

    @Test
    fun `reviewExcuse reject requires supervisor notes`() = runBlocking {
        fakeApi.excusesList.add(sampleExcuse("e1", "طالب 1", "pending"))
        val vm = SupervisorExcusesViewModel(attendanceRepo)

        vm.reviewExcuse(
            excuseId = "e1",
            action = "reject",
            notes = "   " // blank notes
        )

        assertNotNull(vm.uiState.value.errorMessage)
        assertTrue(vm.uiState.value.errorMessage!!.contains("ملاحظات المشرفة مطلوبة"))
        assertNull(fakeApi.lastReviewAction)
    }

    @Test
    fun `reviewExcuse needs_info requires supervisor notes and succeeds when provided`() = runBlocking {
        fakeApi.excusesList.add(sampleExcuse("e1", "طالب 1", "pending"))
        val vm = SupervisorExcusesViewModel(attendanceRepo)

        // Try blank first
        vm.reviewExcuse("e1", "needs_info", "")
        assertNotNull(vm.uiState.value.errorMessage)

        // Provide valid notes
        vm.reviewExcuse("e1", "needs_info", "يرجى إرفاق التقرير الطبي بصيغة واضحة")
        assertEquals("needs_info", fakeApi.lastReviewAction)
        assertEquals("يرجى إرفاق التقرير الطبي بصيغة واضحة", fakeApi.lastReviewNotes)
    }

    @Test
    fun `reviewExcuse handles 409 conflict gracefully`() = runBlocking {
        fakeApi.excusesList.add(sampleExcuse("e1", "طالب 1", "pending"))
        fakeApi.shouldThrowConflict = true
        val vm = SupervisorExcusesViewModel(attendanceRepo)

        vm.reviewExcuse("e1", "approve")

        assertNotNull(vm.uiState.value.errorMessage)
        assertTrue(vm.uiState.value.errorMessage!!.contains("تم البت في هذا العذر مسبقاً"))
    }
}
