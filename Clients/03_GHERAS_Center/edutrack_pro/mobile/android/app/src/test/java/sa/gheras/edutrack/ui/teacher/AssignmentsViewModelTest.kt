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
import sa.gheras.edutrack.data.local.session.FakeSessionStore
import sa.gheras.edutrack.data.local.session.Role
import sa.gheras.edutrack.data.local.session.SessionUser
import sa.gheras.edutrack.data.remote.dto.ProfileDto
import sa.gheras.edutrack.data.remote.dto.ProfileScopeDto
import sa.gheras.edutrack.data.remote.dto.ProfileUserDto
import sa.gheras.edutrack.data.repo.AssignmentsRepository
import sa.gheras.edutrack.data.repo.StudentsRepository
import java.time.Instant
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AssignmentsViewModelTest {

    private lateinit var db: GherasDatabase
    private lateinit var session: FakeSessionStore
    private lateinit var assignmentsRepo: AssignmentsRepository
    private lateinit var studentsRepo: StudentsRepository
    private val now: Instant = Instant.parse("2026-10-11T08:00:00Z")
    private val today: LocalDate = LocalDate.parse("2026-10-11")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, GherasDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        session = FakeSessionStore()
        assignmentsRepo = AssignmentsRepository(
            assignmentDao = db.assignmentDao(),
            submissionDao = db.submissionDao(),
            assignmentStudentDao = db.assignmentStudentDao()
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

    private fun assignment(id: String, title: String, subject: String?, dueDate: LocalDate) = AssignmentEntity(
        id = id,
        branchId = "b1",
        title = title,
        subject = subject,
        kind = "homework",
        dueDate = dueDate,
        teacherUserId = "t1",
        instructions = "حل التمارين",
        pageRef = "ص 15",
        createdAt = now,
        updatedAt = now
    )

    private fun loginAs(role: Role, roomIds: List<String>) {
        session.user = SessionUser(id = "u1", username = "teacher1", role = role, name = "معلم 1")
        session.profile = ProfileDto(
            user = ProfileUserDto(id = "u1", username = "teacher1", role = role.name.lowercase(), name = "معلم 1"),
            scope = ProfileScopeDto(roomIds = roomIds)
        )
    }

    private fun seedData() = runBlocking {
        db.roomDao().upsertAll(
            listOf(
                room("r1", "قاعة الفاروق"),
                room("r2", "قاعة الصديق")
            )
        )
        db.studentDao().upsertAll(
            listOf(
                student("s1", "أحمد علي", "r1"),
                student("s2", "سارة محمد", "r1"),
                student("s3", "خالد يوسف", "r2")
            )
        )
        db.assignmentDao().upsertAll(
            listOf(
                // Completed assignment: 2 students assigned, 2 submissions
                assignment("a1", "حفظ سورة النبأ", "القرآن", today.plusDays(2)),
                // Past due assignment: 2 students assigned, 0 submissions, due yesterday
                assignment("a2", "إعراب الفاعل", "لغتي", today.minusDays(1))
            )
        )
        db.assignmentStudentDao().upsertAll(
            listOf(
                AssignmentStudentEntity(id = "as1", branchId = "b1", assignmentId = "a1", studentId = "s1", createdAt = now, updatedAt = now),
                AssignmentStudentEntity(id = "as2", branchId = "b1", assignmentId = "a1", studentId = "s2", createdAt = now, updatedAt = now),
                AssignmentStudentEntity(id = "as3", branchId = "b1", assignmentId = "a2", studentId = "s1", createdAt = now, updatedAt = now),
                AssignmentStudentEntity(id = "as4", branchId = "b1", assignmentId = "a2", studentId = "s2", createdAt = now, updatedAt = now)
            )
        )
        db.submissionDao().upsertAll(
            listOf(
                SubmissionEntity(id = "sub1", branchId = "b1", assignmentId = "a1", studentId = "s1", submittedAt = now, status = "submitted", grade = null, feedback = null, createdAt = now, updatedAt = now),
                SubmissionEntity(id = "sub2", branchId = "b1", assignmentId = "a1", studentId = "s2", submittedAt = now, status = "submitted", grade = null, feedback = null, createdAt = now, updatedAt = now)
            )
        )
    }

    private fun createVm() = AssignmentsViewModel(
        assignmentsRepository = assignmentsRepo,
        roomDao = db.roomDao(),
        assignmentStudentDao = db.assignmentStudentDao(),
        studentsRepository = studentsRepo,
        submissionDao = db.submissionDao(),
        sessionStore = session,
        pullSync = null
    )

    @Test
    fun `computes submission stats and completion status correctly`() = runBlocking {
        seedData()
        val vm = createVm()
        val state = vm.uiState.first { it.assignments.isNotEmpty() }

        assertEquals(2, state.assignments.size)

        val a1Item = state.assignments.first { it.assignment.id == "a1" }
        assertEquals("قاعة الفاروق", a1Item.roomName)
        assertEquals(2, a1Item.assignedCount)
        assertEquals(2, a1Item.submittedCount)
        assertTrue(a1Item.isCompleted)
        assertFalse(a1Item.isPastDue)

        val a2Item = state.assignments.first { it.assignment.id == "a2" }
        assertEquals(2, a2Item.assignedCount)
        assertEquals(0, a2Item.submittedCount)
        assertFalse(a2Item.isCompleted)
        assertTrue(a2Item.isPastDue)
    }

    @Test
    fun `status filter filters items and computes counter badges`() = runBlocking {
        seedData()
        val vm = createVm()
        var state = vm.uiState.first { it.assignments.isNotEmpty() }

        assertEquals(2, state.totalCount)
        assertEquals(1, state.completedCount)
        // Fixture has no active item: a1 is completed, a2 is past due
        assertEquals(0, state.activeCount)

        // Filter Completed
        vm.setStatusFilter(AssignmentStatusFilter.COMPLETED)
        state = vm.uiState.first { it.statusFilter == AssignmentStatusFilter.COMPLETED }
        assertEquals(1, state.assignments.size)
        assertEquals("a1", state.assignments.first().assignment.id)

        // Filter Past Due
        vm.setStatusFilter(AssignmentStatusFilter.PAST_DUE)
        state = vm.uiState.first { it.statusFilter == AssignmentStatusFilter.PAST_DUE }
        assertEquals(1, state.assignments.size)
        assertEquals("a2", state.assignments.first().assignment.id)

        // Filter All
        vm.setStatusFilter(AssignmentStatusFilter.ALL)
        state = vm.uiState.first { it.statusFilter == AssignmentStatusFilter.ALL }
        assertEquals(2, state.assignments.size)
    }

    @Test
    fun `search query filters by title and subject`() = runBlocking {
        seedData()
        val vm = createVm()

        vm.onSearchQueryChange("القرآن")
        var state = vm.uiState.first { it.searchQuery == "القرآن" }
        assertEquals(1, state.assignments.size)
        assertEquals("a1", state.assignments.first().assignment.id)

        vm.onSearchQueryChange("الفاعل")
        state = vm.uiState.first { it.searchQuery == "الفاعل" }
        assertEquals(1, state.assignments.size)
        assertEquals("a2", state.assignments.first().assignment.id)

        vm.onSearchQueryChange("غير موجود")
        state = vm.uiState.first { it.searchQuery == "غير موجود" }
        assertEquals(0, state.assignments.size)
    }

    @Test
    fun `teacher scope restricts visible rooms`() = runBlocking {
        seedData()
        loginAs(Role.TEACHER, listOf("r1"))

        val vm = createVm()
        val state = vm.uiState.first { it.rooms.isNotEmpty() }

        assertTrue(state.isScopedToAssigned)
        assertEquals(1, state.rooms.size)
        assertEquals("r1", state.rooms.first().id)
    }
}
