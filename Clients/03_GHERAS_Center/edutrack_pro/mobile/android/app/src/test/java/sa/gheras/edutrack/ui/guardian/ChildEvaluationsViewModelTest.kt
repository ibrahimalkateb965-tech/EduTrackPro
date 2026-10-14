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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import sa.gheras.edutrack.data.db.GherasDatabase
import sa.gheras.edutrack.data.entity.EvaluationEntity
import sa.gheras.edutrack.data.entity.StudentEntity
import sa.gheras.edutrack.data.repo.EvaluationsRepository
import sa.gheras.edutrack.data.repo.StudentsRepository
import sa.gheras.edutrack.sync.Outbox
import java.time.Instant
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChildEvaluationsViewModelTest {

    private lateinit var db: GherasDatabase
    private lateinit var studentsRepo: StudentsRepository
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
        evaluationsRepo = EvaluationsRepository(db.evaluationDao(), outbox)
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

    private fun createVm() = ChildEvaluationsViewModel(
        studentId = "s1",
        studentsRepository = studentsRepo,
        evaluationsRepository = evaluationsRepo
    )

    @Test
    fun `loads student name, distinct subjects, and all evaluations`() = runBlocking {
        db.studentDao().upsertAll(listOf(student("s1", "ريان محمد")))
        db.evaluationDao().upsertAll(
            listOf(
                eval("e1", "القرآن", 9.5, today),
                eval("e2", "لغتي", 8.5, today.minusDays(1)),
                eval("e3", "الرياضيات", 10.0, today.minusDays(2))
            )
        )

        val vm = createVm()
        val state = vm.uiState.first { !it.isLoading && it.evaluations.isNotEmpty() }

        assertFalse(state.isLoading)
        assertEquals("ريان محمد", state.studentName)
        assertEquals(3, state.availableSubjects.size)
        assertEquals(3, state.evaluations.size)
        assertNull(state.selectedSubject)
    }

    @Test
    fun `filters evaluations by selected subject`() = runBlocking {
        db.studentDao().upsertAll(listOf(student("s1", "ريان محمد")))
        db.evaluationDao().upsertAll(
            listOf(
                eval("e1", "القرآن", 9.5, today),
                eval("e2", "لغتي", 8.5, today.minusDays(1))
            )
        )

        val vm = createVm()
        vm.uiState.first { !it.isLoading && it.evaluations.isNotEmpty() }

        vm.selectSubject("القرآن")
        var state = vm.uiState.first { it.selectedSubject == "القرآن" }
        assertEquals(1, state.evaluations.size)
        assertEquals("القرآن", state.evaluations.first().subject)

        // Reset to all
        vm.selectSubject(null)
        state = vm.uiState.first { it.selectedSubject == null }
        assertEquals(2, state.evaluations.size)
    }
}
