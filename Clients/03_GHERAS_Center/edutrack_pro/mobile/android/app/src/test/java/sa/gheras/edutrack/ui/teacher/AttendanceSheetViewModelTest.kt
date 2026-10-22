package sa.gheras.edutrack.ui.teacher

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
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import sa.gheras.edutrack.data.db.GherasDatabase
import sa.gheras.edutrack.data.entity.PendingWriteEntity
import sa.gheras.edutrack.data.entity.RoomEntity
import sa.gheras.edutrack.data.entity.StudentAttendanceEntity
import sa.gheras.edutrack.data.entity.StudentEntity
import sa.gheras.edutrack.data.repo.AttendanceRepository
import sa.gheras.edutrack.data.repo.StudentsRepository
import sa.gheras.edutrack.sync.Outbox
import java.time.Instant
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AttendanceSheetViewModelTest {

    private lateinit var db: GherasDatabase
    private lateinit var outbox: Outbox
    private lateinit var studentsRepo: StudentsRepository
    private lateinit var attendanceRepo: AttendanceRepository
    private val now: Instant = Instant.parse("2026-10-11T08:00:00Z")
    private val testDate = LocalDate.parse("2026-10-11")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, GherasDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        outbox = Outbox(context, db)
        studentsRepo = StudentsRepository(db.studentDao())
        attendanceRepo = AttendanceRepository(db.studentAttendanceDao(), db.attendanceExcuseDao(), null, outbox)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun room(id: String, name: String = "قاعة $id") = RoomEntity(
        id = id, branchId = "b1", name = name, groupName = "g",
        createdAt = now, updatedAt = now
    )

    private fun student(id: String, name: String, roomId: String) = StudentEntity(
        id = id, branchId = "b1", name = name, nationalId = null, birthDate = null,
        nationality = null, difficultyNotes = null, childNotes = null, fatherName = null,
        fatherPhone = null, motherName = null, motherPhone = null, guardianPhone = null,
        guardianRelation = null, pickupType = null, pickupName = null, pickupRelation = null,
        pickupPhone = null, previousSchool = null, previousLevel = null, educationNotes = null,
        roomId = roomId, groupName = null, createdAt = now, updatedAt = now
    )

    private fun seedData() = runBlocking {
        db.roomDao().upsertAll(listOf(room("r1", "قاعة الفاروق")))
        db.studentDao().upsertAll(
            listOf(
                student("s1", "أحمد علي", "r1"),
                student("s2", "سارة محمد", "r1")
            )
        )
    }

    private fun createVm(roomId: String = "r1", dateStr: String = "2026-10-11") =
        AttendanceSheetViewModel(
            roomId = roomId,
            dateStr = dateStr,
            studentsRepository = studentsRepo,
            roomDao = db.roomDao(),
            attendanceRepository = attendanceRepo
        )

    private suspend fun loadedState(vm: AttendanceSheetViewModel): AttendanceSheetUiState =
        withTimeout(5_000) {
            vm.uiState.first { it.drafts.isNotEmpty() }
        }

    @Test
    fun `initializes with room students and null status without default present`() = runBlocking {
        seedData()

        val vm = createVm()
        val state = loadedState(vm)

        assertEquals("قاعة الفاروق", state.roomName)
        assertEquals(testDate, state.date)
        assertEquals(2, state.drafts.size)
        // P1 / G3: Must NOT default to "حاضر"
        assertNull(state.drafts[0].status)
        assertNull(state.drafts[1].status)
        assertFalse("Cannot approve until all marked", state.canApprove)
        assertFalse(state.isDirty)
        assertFalse(state.isSaving)
    }

    @Test
    fun `loads existing attendance records for target date`() = runBlocking {
        seedData()
        db.studentAttendanceDao().upsertAll(
            listOf(
                StudentAttendanceEntity(
                    id = "att1",
                    branchId = "b1",
                    studentId = "s1",
                    date = testDate,
                    status = "غائب",
                    note = "عذر مرضي",
                    recordedByUserId = "u1",
                    createdAt = now,
                    updatedAt = now
                )
            )
        )

        val vm = createVm()
        val state = loadedState(vm)

        assertEquals(2, state.drafts.size)
        val s1Draft = state.drafts.first { it.student.id == "s1" }
        assertEquals("غائب", s1Draft.status)
        assertEquals("عذر مرضي", s1Draft.note)

        val s2Draft = state.drafts.first { it.student.id == "s2" }
        assertNull(s2Draft.status)
    }

    @Test
    fun `marking all students enables canApprove`() = runBlocking {
        seedData()
        val vm = createVm()
        loadedState(vm)

        vm.updateStatus("s1", "حاضر")
        assertFalse(vm.uiState.value.canApprove)

        vm.updateStatus("s2", "متأخر")
        assertTrue("All students marked so canApprove must be true", vm.uiState.value.canApprove)
    }

    @Test
    fun `saveDraft writes batch to outbox and resets dirty flag`() = runBlocking {
        seedData()
        val vm = createVm()
        loadedState(vm)
        vm.updateStatus("s1", "غائب")
        vm.updateNote("s1", "سفر عائلي")

        val savedDeferred = CompletableDeferred<Unit>()
        vm.saveDraft { savedDeferred.complete(Unit) }
        savedDeferred.await()

        val finalState = vm.uiState.first { !it.isSaving && !it.isDirty }
        assertFalse(finalState.isDirty)
        assertFalse(finalState.isSaving)

        // Verify records were enqueued in Outbox with KIND_ATTENDANCE_RECORD
        val pendingWrites = db.pendingWriteDao().observePending().first()
        assertEquals(1, pendingWrites.size)
        assertEquals(PendingWriteEntity.KIND_ATTENDANCE_RECORD, pendingWrites[0].kind)
    }

    @Test
    fun `approve enqueues draft batch and approval pending write`() = runBlocking {
        seedData()
        val vm = createVm()
        loadedState(vm)
        vm.updateStatus("s1", "حاضر")
        vm.updateStatus("s2", "حاضر")

        val approvedDeferred = CompletableDeferred<Unit>()
        vm.approve { approvedDeferred.complete(Unit) }
        approvedDeferred.await()

        val pendingWrites = db.pendingWriteDao().observePending().first()
        // 2 attendance records + 1 approval record = 3
        assertEquals(3, pendingWrites.size)
        val approvalWrite = pendingWrites.firstOrNull { it.kind == PendingWriteEntity.KIND_ATTENDANCE_APPROVE }
        assertNotNull("Must enqueue approval pending write", approvalWrite)
        assertEquals("att_approve:r1:$testDate", approvalWrite?.naturalKey)
    }
}
