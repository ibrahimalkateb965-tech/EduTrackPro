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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import sa.gheras.edutrack.data.db.GherasDatabase
import sa.gheras.edutrack.data.entity.AssignmentEntity
import sa.gheras.edutrack.data.entity.AssignmentStudentEntity
import sa.gheras.edutrack.data.entity.RoomEntity
import sa.gheras.edutrack.data.entity.StudentEntity
import sa.gheras.edutrack.data.entity.SubmissionEntity
import sa.gheras.edutrack.data.remote.fakes.FakeMeApi
import sa.gheras.edutrack.data.repo.AssignmentsRepository
import sa.gheras.edutrack.data.repo.StudentsRepository
import java.time.Instant
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AssignmentDetailViewModelTest {

    private lateinit var db: GherasDatabase
    private lateinit var fakeMeApi: FakeMeApi
    private lateinit var assignmentsRepo: AssignmentsRepository
    private lateinit var studentsRepo: StudentsRepository
    private val now: Instant = Instant.now()
    private val today: LocalDate get() = LocalDate.now()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, GherasDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        fakeMeApi = FakeMeApi()
        assignmentsRepo = AssignmentsRepository(
            assignmentDao = db.assignmentDao(),
            submissionDao = db.submissionDao(),
            assignmentStudentDao = db.assignmentStudentDao(),
            meApi = fakeMeApi
        )
        studentsRepo = StudentsRepository(db.studentDao())
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

    private fun assignment(id: String, title: String) = AssignmentEntity(
        id = id,
        branchId = "b1",
        title = title,
        subject = "القرآن",
        kind = "homework",
        dueDate = today.plusDays(3),
        teacherUserId = "u1",
        instructions = "حفظ السورة",
        pageRef = "ص 10",
        createdAt = now,
        updatedAt = now
    )

    private fun seed() = runBlocking {
        db.roomDao().upsertAll(listOf(room("r1", "قاعة الفاروق")))
        db.studentDao().upsertAll(
            listOf(
                student("s1", "أحمد علي", "r1"),
                student("s2", "سارة محمد", "r1")
            )
        )
        db.assignmentDao().upsertAll(listOf(assignment("a1", "حفظ سورة الأعلى")))
        db.assignmentStudentDao().upsertAll(
            listOf(
                AssignmentStudentEntity(id = "as1", branchId = "b1", assignmentId = "a1", studentId = "s1", createdAt = now, updatedAt = now),
                AssignmentStudentEntity(id = "as2", branchId = "b1", assignmentId = "a1", studentId = "s2", createdAt = now, updatedAt = now)
            )
        )
        db.submissionDao().upsertAll(
            listOf(
                SubmissionEntity(
                    id = "sub1",
                    branchId = "b1",
                    assignmentId = "a1",
                    studentId = "s1",
                    submittedAt = now,
                    status = "submitted",
                    grade = null,
                    feedback = null,
                    createdAt = now,
                    updatedAt = now
                )
            )
        )
    }

    private fun createVm(assignmentId: String = "a1") = AssignmentDetailViewModel(
        assignmentId = assignmentId,
        assignmentsRepository = assignmentsRepo,
        assignmentStudentDao = db.assignmentStudentDao(),
        studentsRepository = studentsRepo,
        roomDao = db.roomDao(),
        pullSync = null
    )

    @Test
    fun `loads assignment details and computes correct submission counts`() = runBlocking {
        seed()
        val vm = createVm()
        val state = vm.uiState.first { it.assignment != null }

        assertEquals("حفظ سورة الأعلى", state.assignment?.title)
        assertEquals("قاعة الفاروق", state.roomName)
        assertEquals(2, state.totalCount)
        assertEquals(1, state.submittedCount)
        assertEquals(1, state.pendingCount)
        assertEquals(2, state.studentSubmissions.size)

        val s1Status = state.studentSubmissions.first { it.student.id == "s1" }
        assertTrue(s1Status.isSubmitted)
        assertEquals("sub1", s1Status.submission?.id)

        val s2Status = state.studentSubmissions.first { it.student.id == "s2" }
        assertFalse(s2Status.isSubmitted)
        assertNull(s2Status.submission)
    }

    @Test
    fun `filter switches between all submitted and pending submissions`() = runBlocking {
        seed()
        val vm = createVm()
        vm.uiState.first { it.assignment != null }

        // Filter SUBMITTED
        vm.setFilter(SubmissionFilter.SUBMITTED)
        var state = vm.uiState.first { it.filter == SubmissionFilter.SUBMITTED }
        assertEquals(1, state.studentSubmissions.size)
        assertEquals("s1", state.studentSubmissions.first().student.id)

        // Filter PENDING
        vm.setFilter(SubmissionFilter.PENDING)
        state = vm.uiState.first { it.filter == SubmissionFilter.PENDING }
        assertEquals(1, state.studentSubmissions.size)
        assertEquals("s2", state.studentSubmissions.first().student.id)

        // Filter ALL
        vm.setFilter(SubmissionFilter.ALL)
        state = vm.uiState.first { it.filter == SubmissionFilter.ALL }
        assertEquals(2, state.studentSubmissions.size)
    }

    @Test
    fun `gradeSubmission updates grade and feedback via repository`() = runBlocking {
        seed()
        fakeMeApi.gradeAssignmentId = "a1"
        fakeMeApi.gradeStudentId = "s1"
        val vm = createVm()
        vm.uiState.first { it.assignment != null }

        vm.gradeSubmission("sub1", 9.5, "أداء ممتاز")
        val state = vm.uiState.first { !it.isGrading && it.studentSubmissions.any { sub -> sub.submission?.grade == 9.5 } }

        assertNull(state.gradingError)
        // Verify in DB
        val updated = db.submissionDao().getById("sub1")
        assertNotNull(updated)
        assertEquals(9.5, updated?.grade ?: 0.0, 0.01)
        assertEquals("أداء ممتاز", updated?.feedback)
    }
}
