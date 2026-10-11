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
import kotlinx.coroutines.withTimeout
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
import sa.gheras.edutrack.data.entity.EvaluationEntity
import sa.gheras.edutrack.data.entity.InstallmentEntity
import sa.gheras.edutrack.data.entity.StudentAttendanceEntity
import sa.gheras.edutrack.data.entity.StudentEntity
import sa.gheras.edutrack.data.repo.AssignmentsRepository
import sa.gheras.edutrack.data.repo.AttendanceRepository
import sa.gheras.edutrack.data.repo.EvaluationsRepository
import sa.gheras.edutrack.data.repo.FeesRepository
import sa.gheras.edutrack.data.repo.StudentsRepository
import sa.gheras.edutrack.sync.Outbox
import java.time.Instant
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GuardianHomeViewModelTest {

    private lateinit var db: GherasDatabase
    private lateinit var outbox: Outbox
    private val now: Instant = Instant.parse("2026-10-11T12:00:00Z")
    private val today: LocalDate = LocalDate.now()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, GherasDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        outbox = Outbox(context, db)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun student(id: String, name: String) = StudentEntity(
        id = id, branchId = "b1", name = name, nationalId = "1000$id", birthDate = null,
        nationality = null, difficultyNotes = null, childNotes = null, fatherName = "الأب",
        fatherPhone = null, motherName = null, motherPhone = null, guardianPhone = "0501234567",
        guardianRelation = "أب", pickupType = null, pickupName = null, pickupRelation = null,
        pickupPhone = null, previousSchool = null, previousLevel = null, educationNotes = null,
        roomId = null, groupName = null, createdAt = now, updatedAt = now
    )

    private fun newVm() = GuardianHomeViewModel(
        studentsRepository = StudentsRepository(db.studentDao()),
        attendanceRepository = AttendanceRepository(db.studentAttendanceDao(), outbox),
        evaluationsRepository = EvaluationsRepository(db.evaluationDao(), outbox),
        assignmentsRepository = AssignmentsRepository(db.assignmentDao(), db.submissionDao()),
        feesRepository = FeesRepository(db.installmentDao(), db.receiptDao()),
        pullSync = null
    )

    @Test
    fun `when children list is empty, state has empty children and is not loading`() = runBlocking {
        val vm = newVm()
        val state = withTimeout(5_000) { vm.uiState.first { !it.isLoading } }

        assertTrue(state.children.isEmpty())
        assertNull(state.selectedChild)
        assertFalse(state.isRefreshing)
    }

    @Test
    fun `when children exist, default selected child is the first child`() = runBlocking {
        // StudentDao sorts by name ASC, so "أحمد" (s1) correctly precedes "سارة" (s2)
        db.studentDao().upsertAll(listOf(student("s1", "أحمد"), student("s2", "سارة")))

        val vm = newVm()
        val state = withTimeout(5_000) { vm.uiState.first { !it.isLoading } }

        assertEquals(2, state.children.size)
        assertEquals("s1", state.selectedChild?.id)
        assertEquals("أحمد", state.selectedChild?.name)
    }

    @Test
    fun `selecting child updates active child in dashboard`() = runBlocking {
        db.studentDao().upsertAll(listOf(student("s1", "أحمد"), student("s2", "سارة")))

        val vm = newVm()
        withTimeout(5_000) { vm.uiState.first { !it.isLoading } }

        vm.selectChild("s2")
        val updated = withTimeout(5_000) { vm.uiState.first { it.selectedChild?.id == "s2" } }

        assertEquals("s2", updated.selectedChild?.id)
        assertEquals("سارة", updated.selectedChild?.name)
    }

    @Test
    fun `dashboard aggregates attendance, evaluations, pending homework and installments`() = runBlocking {
        val s = student("s1", "أحمد")
        db.studentDao().upsert(s)

        // Attendance
        db.studentAttendanceDao().upsert(
            StudentAttendanceEntity(
                id = "att1", branchId = "b1", studentId = "s1",
                date = today, status = "present", note = "حاضر في الموعد",
                recordedByUserId = "u1", createdAt = now, updatedAt = now
            )
        )

        // Evaluations
        listOf("القرآن", "التوحيد", "الفقه", "الحديث", "اللغة العربية").forEachIndexed { i, subject ->
            db.evaluationDao().upsert(
                EvaluationEntity(
                    id = "eval$i", branchId = "b1", studentId = "s1",
                    subject = subject, evalType = "daily", date = today.minusDays(i.toLong()),
                    value = 9.0 + (i * 0.2), teacherUserId = "u1",
                    createdAt = now, updatedAt = now
                )
            )
        }

        // Assignments
        val a1 = AssignmentEntity(
            id = "asg1", branchId = "b1", title = "حفظ سورة النبأ", subject = "القرآن",
            kind = "memorization", dueDate = today.plusDays(2), teacherUserId = "u1",
            instructions = null, pageRef = "1", createdAt = now, updatedAt = now
        )
        val a2 = AssignmentEntity(
            id = "asg2", branchId = "b1", title = "واجب الفقه", subject = "الفقه",
            kind = "homework", dueDate = today.minusDays(5), teacherUserId = "u1",
            instructions = null, pageRef = "10", createdAt = now, updatedAt = now
        )
        db.assignmentDao().upsertAll(listOf(a1, a2))
        db.assignmentStudentDao().upsertAll(
            listOf(
                AssignmentStudentEntity(id = "as_s1_1", branchId = "b1", assignmentId = "asg1", studentId = "s1", createdAt = now, updatedAt = now),
                AssignmentStudentEntity(id = "as_s1_2", branchId = "b1", assignmentId = "asg2", studentId = "s1", createdAt = now, updatedAt = now)
            )
        )

        // Installments
        db.installmentDao().upsertAll(
            listOf(
                InstallmentEntity(
                    id = "inst1", branchId = "b1", studentId = "s1", feePlanId = "fp1",
                    seqNo = 1, dueDate = today.minusDays(30), amount = 500.0, paidAmount = 500.0,
                    status = "paid", createdAt = now, updatedAt = now
                ),
                InstallmentEntity(
                    id = "inst2", branchId = "b1", studentId = "s1", feePlanId = "fp1",
                    seqNo = 2, dueDate = today.plusDays(15), amount = 500.0, paidAmount = 0.0,
                    status = "pending", createdAt = now, updatedAt = now
                )
            )
        )

        val vm = newVm()
        val state = withTimeout(5_000) { vm.uiState.first { !it.isLoading && it.todayAttendance != null } }

        assertNotNull(state.todayAttendance)
        assertEquals("present", state.todayAttendance?.status)
        assertEquals(4, state.latestEvaluations.size) // Capped at 4 subjects
        assertEquals(1, state.pendingHomeworkCount) // Only future/today assignment
        assertEquals("inst2", state.nextUnpaidInstallment?.id)
    }
}
