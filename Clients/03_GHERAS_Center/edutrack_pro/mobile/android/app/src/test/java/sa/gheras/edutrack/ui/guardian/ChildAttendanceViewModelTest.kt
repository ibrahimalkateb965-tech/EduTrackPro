package sa.gheras.edutrack.ui.guardian

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import sa.gheras.edutrack.data.db.GherasDatabase
import sa.gheras.edutrack.data.entity.PendingWriteEntity
import sa.gheras.edutrack.data.entity.StudentAttendanceEntity
import sa.gheras.edutrack.data.entity.StudentEntity
import sa.gheras.edutrack.data.repo.AttendanceRepository
import sa.gheras.edutrack.data.repo.StudentsRepository
import sa.gheras.edutrack.sync.Outbox
import java.time.Instant
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChildAttendanceViewModelTest {

    private lateinit var db: GherasDatabase
    private lateinit var studentsRepo: StudentsRepository
    private lateinit var attendanceRepo: AttendanceRepository
    private val now: Instant = Instant.now()
    private val today: LocalDate get() = LocalDate.now()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, GherasDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val outbox = Outbox(context, db) { "guardian_1" }
        studentsRepo = StudentsRepository(db.studentDao())
        attendanceRepo = AttendanceRepository(db.studentAttendanceDao(), db.attendanceExcuseDao(), null, outbox)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun student(id: String, name: String) = StudentEntity(
        id = id, branchId = "b1", name = name, nationalId = null, birthDate = null,
        nationality = null, difficultyNotes = null, childNotes = null, fatherName = null,
        fatherPhone = null, motherName = null, motherPhone = null, guardianPhone = null,
        guardianRelation = null, pickupType = null, pickupName = null, pickupRelation = null,
        pickupPhone = null, previousSchool = null, previousLevel = null, educationNotes = null,
        roomId = null, groupName = null, createdAt = now, updatedAt = now
    )

    private fun att(id: String, status: String, date: LocalDate, isDraft: Boolean = false) = StudentAttendanceEntity(
        id = id,
        branchId = "b1",
        studentId = "s1",
        date = date,
        status = status,
        note = null,
        isDraft = isDraft,
        recordedByUserId = "u1",
        createdAt = now,
        updatedAt = now
    )

    private fun createVm() = ChildAttendanceViewModel(
        studentId = "s1",
        studentsRepository = studentsRepo,
        attendanceRepository = attendanceRepo
    )

    @Test
    fun `loads student name and filters records within 60 days excluding drafts`() = runBlocking {
        db.studentDao().upsertAll(listOf(student("s1", "ريان محمد")))
        db.studentAttendanceDao().upsertAll(
            listOf(
                att("a1", "حاضر", today),
                att("a2", "غائب", today.minusDays(5)),
                att("a3", "متأخر", today.minusDays(15)),
                att("a4", "مستأذن", today.minusDays(25)),
                // Record with approved excuse counts towards excused
                att("a5", "غياب بعذر", today.minusDays(30)),
                // Draft record must be hidden from guardian (P4)
                att("a6", "غائب", today.minusDays(2), isDraft = true),
                // Record older than 60 days
                att("a7", "حاضر", today.minusDays(70))
            )
        )

        val vm = createVm()
        val state = vm.uiState.first { !it.isLoading && it.records.isNotEmpty() }

        assertFalse(state.isLoading)
        assertEquals("ريان محمد", state.studentName)
        // Only 5 non-draft records within 60 days
        assertEquals(5, state.records.size)
        assertEquals(1, state.presentCount)
        assertEquals(1, state.absentCount)
        assertEquals(1, state.lateCount)
        // 1 مستأذن + 1 غياب بعذر = 2 (P3 / G4)
        assertEquals(2, state.excusedCount)
    }

    @Test
    fun `submitExcuse validates minimum 5 characters and enqueues to outbox`() = runBlocking {
        db.studentDao().upsertAll(listOf(student("s1", "ريان محمد")))
        val vm = createVm()

        // 1. Validation error on short reason
        vm.submitExcuse(today, "مرض") {}
        assertEquals("سبب العذر يجب ألا يقل عن 5 أحرف", vm.uiState.value.excuseError)

        // 2. Successful submission
        val submitted = CompletableDeferred<Unit>()
        vm.submitExcuse(today, "مرض شديد مع مراجعة الطبيب") {
            submitted.complete(Unit)
        }
        submitted.await()

        val pending = db.pendingWriteDao().listPending()
        assertEquals(1, pending.size)
        assertEquals(PendingWriteEntity.KIND_ATTENDANCE_EXCUSE, pending[0].kind)

        // Optimistic excuse is observed in UI state
        val state = vm.uiState.first { it.excuses.isNotEmpty() }
        assertEquals(1, state.excuses.size)
        assertEquals("مرض شديد مع مراجعة الطبيب", state.excuses[0].reasonText)
    }
}
