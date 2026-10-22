package sa.gheras.edutrack.ui.teacher

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import sa.gheras.edutrack.data.db.GherasDatabase
import sa.gheras.edutrack.data.entity.EvaluationEntity
import sa.gheras.edutrack.data.entity.RoomEntity
import sa.gheras.edutrack.data.entity.StudentAttendanceEntity
import sa.gheras.edutrack.data.entity.StudentEntity
import sa.gheras.edutrack.data.repo.AttendanceRepository
import sa.gheras.edutrack.data.repo.EvaluationsRepository
import sa.gheras.edutrack.data.repo.StudentsRepository
import sa.gheras.edutrack.sync.Outbox
import java.time.Instant
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StudentDetailViewModelTest {

    private lateinit var db: GherasDatabase
    private lateinit var studentsRepo: StudentsRepository
    private lateinit var attendanceRepo: AttendanceRepository
    private lateinit var evaluationsRepo: EvaluationsRepository
    private val now: Instant = Instant.now()
    private val today: LocalDate get() = LocalDate.now()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, GherasDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val outbox = Outbox(context, db)
        studentsRepo = StudentsRepository(db.studentDao())
        attendanceRepo = AttendanceRepository(attendanceDao = db.studentAttendanceDao(), outbox = outbox)
        evaluationsRepo = EvaluationsRepository(db.evaluationDao(), outbox)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun room(id: String, name: String) = RoomEntity(
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

    private fun att(id: String, status: String, date: LocalDate) = StudentAttendanceEntity(
        id = id,
        branchId = "b1",
        studentId = "s1",
        date = date,
        status = status,
        note = null,
        recordedByUserId = "u1",
        createdAt = now,
        updatedAt = now
    )

    private fun eval(id: String, subject: String, score: Double, date: LocalDate) = EvaluationEntity(
        id = id,
        branchId = "b1",
        studentId = "s1",
        subject = subject,
        evalType = "daily",
        date = date,
        value = score,
        teacherUserId = "u1",
        createdAt = now,
        updatedAt = now
    )

    private fun createVm() = StudentDetailViewModel(
        studentId = "s1",
        studentsRepository = studentsRepo,
        roomDao = db.roomDao(),
        attendanceRepository = attendanceRepo,
        evaluationsRepository = evaluationsRepo,
        skillProgressDao = db.skillProgressDao()
    )

    @Test
    fun `loads student and room name correctly`() = runBlocking {
        db.roomDao().upsertAll(listOf(room("r1", "قاعة الفاروق")))
        db.studentDao().upsertAll(listOf(student("s1", "أحمد علي", "r1")))

        val vm = createVm()
        val state = vm.uiState.first { !it.isLoading }

        assertNotNull(state.student)
        assertEquals("أحمد علي", state.student?.name)
        assertEquals("قاعة الفاروق", state.roomName)
    }

    @Test
    fun `computes attendance stats within 60 days and excludes older records`() = runBlocking {
        db.roomDao().upsertAll(listOf(room("r1", "قاعة الفاروق")))
        db.studentDao().upsertAll(listOf(student("s1", "أحمد علي", "r1")))

        db.studentAttendanceDao().upsertAll(
            listOf(
                att("a1", "حاضر", today),
                att("a2", "حاضر", today.minusDays(10)),
                att("a3", "غائب", today.minusDays(20)),
                att("a4", "متأخر", today.minusDays(30)),
                att("a5", "مستأذن", today.minusDays(40)),
                // Record older than 60 days (should be excluded)
                att("a6", "حاضر", today.minusDays(65))
            )
        )

        val vm = createVm()
        val state = vm.uiState.first { !it.isLoading && it.attendanceStats.total > 0 }

        assertEquals(5, state.attendanceStats.total)
        assertEquals(2, state.attendanceStats.present)
        assertEquals(1, state.attendanceStats.absent)
        assertEquals(1, state.attendanceStats.late)
        assertEquals(1, state.attendanceStats.excused)
    }

    @Test
    fun `limits evaluations to latest 10 items`() = runBlocking {
        db.roomDao().upsertAll(listOf(room("r1", "قاعة الفاروق")))
        db.studentDao().upsertAll(listOf(student("s1", "أحمد علي", "r1")))

        val evals = (1..15).map { i ->
            eval("e$i", "القرآن", 9.0, today.minusDays(i.toLong()))
        }
        db.evaluationDao().upsertAll(evals)

        val vm = createVm()
        val state = vm.uiState.first { !it.isLoading && it.latestEvaluations.isNotEmpty() }

        assertEquals(10, state.latestEvaluations.size)
        assertFalse(state.isLoading)
    }
}
