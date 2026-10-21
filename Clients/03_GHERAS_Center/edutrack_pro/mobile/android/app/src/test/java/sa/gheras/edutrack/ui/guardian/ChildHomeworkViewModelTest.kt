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

    private fun assignment(id: String, title: String, dueDate: LocalDate?, isDraft: Boolean = false) = AssignmentEntity(
        id = id,
        branchId = "b1",
        title = title,
        subject = "القرآن الكريم",
        kind = "homework",
        dueDate = dueDate,
        teacherUserId = "t1",
        instructions = "حفظ سورة النبأ",
        pageRef = "صفحة 582",
        isDraft = isDraft,
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

    private fun submission(
        id: String,
        assignmentId: String,
        studentId: String,
        status: String = "submitted",
        attemptNumber: Int = 1,
        gradeLevel: String? = null
    ) = SubmissionEntity(
        id = id,
        branchId = "b1",
        assignmentId = assignmentId,
        studentId = studentId,
        attemptNumber = attemptNumber,
        gradeLevel = gradeLevel,
        submittedAt = now,
        status = status,
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
    fun `loads published assignments only and computes 5 tabs counts`() = runBlocking {
        db.studentDao().upsertAll(listOf(student("s1", "سعود علي")))
        db.assignmentDao().upsertAll(
            listOf(
                assignment("a1", "واجب التلاوة (مطلوب)", today),
                assignment("a2", "واجب التجويد (قيد المراجعة)", today.minusDays(2)),
                assignment("a3", "واجب الحفظ (تمت المراجعة)", today.minusDays(5)),
                assignment("a4", "واجب التفسير (إعادة الحل)", today.minusDays(1)),
                assignment("a_draft", "مسودة لا تظهر", today, isDraft = true)
            )
        )
        db.assignmentStudentDao().upsertAll(
            listOf(
                studentLink("link1", "a1", "s1"),
                studentLink("link2", "a2", "s1"),
                studentLink("link3", "a3", "s1"),
                studentLink("link4", "a4", "s1"),
                studentLink("link5", "a_draft", "s1")
            )
        )
        db.submissionDao().upsertAll(
            listOf(
                submission("sub2", "a2", "s1", status = "submitted"),
                submission("sub3", "a3", "s1", status = "graded", gradeLevel = "EXCELLENT"),
                submission("sub4", "a4", "s1", status = "revision_requested")
            )
        )

        val vm = createVm("s1")
        val state = vm.uiState.first { !it.isLoading && it.items.isNotEmpty() }

        assertFalse(state.isLoading)
        assertEquals("سعود علي", state.studentName)
        assertEquals(4, state.items.size) // Draft excluded!

        // Verify tab counts
        assertEquals(4, state.counts[HomeworkTab.ALL])
        assertEquals(1, state.counts[HomeworkTab.PENDING])
        assertEquals(1, state.counts[HomeworkTab.SUBMITTED])
        assertEquals(1, state.counts[HomeworkTab.GRADED])
        assertEquals(1, state.counts[HomeworkTab.REVISION_REQUESTED])
    }

    @Test
    fun `selectTab filters items appropriately`() = runBlocking {
        db.studentDao().upsertAll(listOf(student("s1", "سعود علي")))
        db.assignmentDao().upsertAll(
            listOf(
                assignment("a1", "واجب 1", today),
                assignment("a2", "واجب 2", today)
            )
        )
        db.assignmentStudentDao().upsertAll(
            listOf(
                studentLink("link1", "a1", "s1"),
                studentLink("link2", "a2", "s1")
            )
        )
        db.submissionDao().upsertAll(
            listOf(
                submission("sub2", "a2", "s1", status = "submitted")
            )
        )

        val vm = createVm("s1")

        // Default ALL: 2 items
        val allState = vm.uiState.first { !it.isLoading && it.items.isNotEmpty() }
        assertEquals(2, allState.items.size)

        // Select PENDING: 1 item (a1)
        vm.selectTab(HomeworkTab.PENDING)
        val pendingState = vm.uiState.first { it.selectedTab == HomeworkTab.PENDING }
        assertEquals(1, pendingState.items.size)
        assertEquals("a1", pendingState.items[0].assignment.id)

        // Select SUBMITTED: 1 item (a2)
        vm.selectTab(HomeworkTab.SUBMITTED)
        val submittedState = vm.uiState.first { it.selectedTab == HomeworkTab.SUBMITTED }
        assertEquals(1, submittedState.items.size)
        assertEquals("a2", submittedState.items[0].assignment.id)

        // Select GRADED: 0 items
        vm.selectTab(HomeworkTab.GRADED)
        val gradedState = vm.uiState.first { it.selectedTab == HomeworkTab.GRADED }
        assertEquals(0, gradedState.items.size)
    }
}
