package sa.gheras.edutrack.ui.guardian

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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import sa.gheras.edutrack.data.db.GherasDatabase
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
        val outbox = Outbox(context, db)
        studentsRepo = StudentsRepository(db.studentDao())
        attendanceRepo = AttendanceRepository(db.studentAttendanceDao(), outbox)
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

    private fun createVm() = ChildAttendanceViewModel(
        studentId = "s1",
        studentsRepository = studentsRepo,
        attendanceRepository = attendanceRepo
    )

    @Test
    fun `loads student name and filters records within 60 days`() = runBlocking {
        db.studentDao().upsertAll(listOf(student("s1", "ريان محمد")))
        db.studentAttendanceDao().upsertAll(
            listOf(
                att("a1", "حاضر", today),
                att("a2", "غائب", today.minusDays(5)),
                att("a3", "متأخر", today.minusDays(15)),
                att("a4", "مستأذن", today.minusDays(25)),
                // Record older than 60 days
                att("a5", "حاضر", today.minusDays(70))
            )
        )

        val vm = createVm()
        val state = vm.uiState.first { !it.isLoading && it.records.isNotEmpty() }

        assertFalse(state.isLoading)
        assertEquals("ريان محمد", state.studentName)
        assertEquals(4, state.records.size)
        assertEquals(1, state.presentCount)
        assertEquals(1, state.absentCount)
        assertEquals(1, state.lateCount)
        assertEquals(1, state.excusedCount)
    }
}
