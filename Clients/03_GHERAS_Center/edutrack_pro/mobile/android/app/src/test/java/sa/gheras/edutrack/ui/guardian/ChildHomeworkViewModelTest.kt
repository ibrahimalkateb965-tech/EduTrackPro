package sa.gheras.edutrack.ui.guardian

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import sa.gheras.edutrack.data.entity.AssignmentEntity
import sa.gheras.edutrack.data.entity.AssignmentStudentEntity
import sa.gheras.edutrack.data.entity.StudentEntity
import sa.gheras.edutrack.data.entity.SubmissionEntity
import sa.gheras.edutrack.data.repo.AssignmentsRepository
import sa.gheras.edutrack.data.repo.StudentsRepository
import java.time.Instant
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChildHomeworkViewModelTest {

    private lateinit var db: GherasDatabase
    private lateinit var studentsRepo: StudentsRepository
    private lateinit var assignmentsRepo: AssignmentsRepository
    private val now: Instant = Instant.now()
    private val today: LocalDate get() = LocalDate.now()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, GherasDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        studentsRepo = StudentsRepository(db.studentDao())
        assignmentsRepo = AssignmentsRepository(
            assignmentDao = db.assignmentDao(),
            submissionDao = db.submissionDao(),
            assignmentStudentDao = db.assignmentStudentDao(),
            submissionFileDao = db.submissionFileDao()
        )
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

    private fun assignment(id: String, title: String, dueDate: LocalDate) = AssignmentEntity(
        id = id,
        branchId = "b1",
        title = title,
        subject = "القرآن الكريم",
        kind = "homework",
        dueDate = dueDate,
        teacherUserId = "t1",
        instructions = "حفظ سورة النبأ",
        pageRef = "صفحة 582",
        createdAt = now,
        updatedAt = now
    )

    private fun studentLink(id: String, assignmentId: String, studentId: String) = AssignmentStudentEntity(
        id = id,
        branchId = "b1",
        assignmentId = assignmentId,
        studentId = studentId,
        createdAt = now,
        updatedAt = now
    )

    private fun submission(id: String, assignmentId: String, studentId: String) = SubmissionEntity(
        id = id,
        branchId = "b1",
        assignmentId = assignmentId,
        studentId = studentId,
        submittedAt = now,
        status = "submitted",
        grade = null,
        feedback = null,
        createdAt = now,
        updatedAt = now
    )

    private fun createVm(studentId: String = "s1") = ChildHomeworkViewModel(
        studentId = studentId,
        studentsRepository = studentsRepo,
        assignmentsRepository = assignmentsRepo,
        assignmentStudentDao = db.assignmentStudentDao()
    )

    @Test
    fun `loads assignments within 60 days with submitted and unsubmitted status`() = runBlocking {
        db.studentDao().upsertAll(listOf(student("s1", "سعود علي")))
        db.assignmentDao().upsertAll(
            listOf(
                assignment("a1", "واجب التلاوة", today),
                assignment("a2", "واجب التجويد", today.minusDays(10)),
                assignment("a3", "واجب قديم جدا", today.minusDays(75)) // Outside 60 days
            )
        )
        db.assignmentStudentDao().upsertAll(
            listOf(
                studentLink("link1", "a1", "s1"),
                studentLink("link2", "a2", "s1"),
                studentLink("link3", "a3", "s1")
            )
        )
        db.submissionDao().upsertAll(
            listOf(
                submission("sub1", "a1", "s1")
            )
        )

        val vm = createVm("s1")
        val state = vm.uiState.first { !it.isLoading && it.items.isNotEmpty() }

        assertFalse(state.isLoading)
        assertEquals("سعود علي", state.studentName)
        assertEquals(2, state.items.size)

        val item1 = state.items.first { it.assignment.id == "a1" }
        assertTrue(item1.isSubmitted)
        assertNotNull(item1.submission)

        val item2 = state.items.first { it.assignment.id == "a2" }
        assertFalse(item2.isSubmitted)
        assertNull(item2.submission)
    }

    @Test
    fun `submitting with empty image list sets validation error`() = runBlocking {
        val vm = createVm("s1")
        val context = ApplicationProvider.getApplicationContext<Context>()

        val job = launch { vm.uiState.collect {} }

        vm.submitHomework(context, "a1", emptyList(), "ملاحظة")

        val state = vm.uiState.first { it.submissionError != null }
        assertEquals("يرجى التقاط أو اختيار صورة واحدة على الأقل للواجب", state.submissionError)
        assertFalse(state.isSubmitting)

        job.cancel()
    }

    @Test
    fun `clearStatus resets submission error and success state`() = runBlocking {
        val vm = createVm("s1")
        val context = ApplicationProvider.getApplicationContext<Context>()

        val job = launch { vm.uiState.collect {} }

        vm.submitHomework(context, "a1", emptyList())
        val errorState = vm.uiState.first { it.submissionError != null }
        assertNotNull(errorState.submissionError)

        vm.clearStatus()
        val clearedState = vm.uiState.first { it.submissionError == null }
        assertNull(clearedState.submissionError)
        assertFalse(clearedState.submissionSuccess)

        job.cancel()
    }
}
