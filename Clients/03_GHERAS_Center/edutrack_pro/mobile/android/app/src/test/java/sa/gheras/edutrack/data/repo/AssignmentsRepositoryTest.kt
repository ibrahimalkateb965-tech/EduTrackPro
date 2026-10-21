package sa.gheras.edutrack.data.repo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
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
import sa.gheras.edutrack.data.remote.fakes.FakeMeApi
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class AssignmentsRepositoryTest {

    private lateinit var db: GherasDatabase
    private lateinit var fakeMeApi: FakeMeApi
    private lateinit var repo: AssignmentsRepository

    private val now = Instant.parse("2026-10-21T10:00:00Z")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, GherasDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        fakeMeApi = FakeMeApi()

        repo = AssignmentsRepository(
            assignmentDao = db.assignmentDao(),
            submissionDao = db.submissionDao(),
            assignmentStudentDao = db.assignmentStudentDao(),
            submissionFileDao = db.submissionFileDao(),
            meApi = fakeMeApi,
            outbox = null
        )

        // Seed students
        kotlinx.coroutines.runBlocking {
            db.studentDao().upsert(
                StudentEntity(
                    id = "student_1",
                    branchId = "b1",
                    name = "Ahmad",
                    gender = "MALE",
                    createdAt = now,
                    updatedAt = now
                )
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testCreateAndPublishAssignment() = runTest {
        // Create draft assignment
        val draft = repo.createAssignment(
            title = "Quran Draft",
            subject = "Islamic",
            dueDate = LocalDate.of(2026, 10, 30),
            instructions = "Memorize verses 1-10",
            pageRef = "p. 500",
            teacherUserId = "teacher_1",
            studentIds = listOf("student_1"),
            branchId = "b1",
            isDraft = true
        )
        assertTrue(draft.isDraft)
        assertEquals("Quran Draft", draft.title)

        // Draft should NOT appear in guardian's observePublishedByStudent
        repo.observePublishedByStudent("student_1").test {
            val list = awaitItem()
            assertTrue("Guardian must not see drafts", list.isEmpty())
        }

        // Draft SHOULD appear in teacher's observeDraftsByTeacher
        repo.observeDraftsByTeacher("teacher_1").test {
            val drafts = awaitItem()
            assertEquals(1, drafts.size)
            assertEquals(draft.id, drafts[0].id)
        }

        // Publish assignment
        val publishResult = repo.publishAssignment(draft.id)
        assertTrue(publishResult.isSuccess)

        // Now guardian SHOULD see it in observePublishedByStudent
        repo.observePublishedByStudent("student_1").test {
            val list = awaitItem()
            assertEquals(1, list.size)
            assertEquals(draft.id, list[0].id)
            assertFalse(list[0].isDraft)
        }
    }

    @Test
    fun testSubmitAssignment_multiAttemptAndLateCheck() = runTest {
        // Create an overdue assignment (due yesterday)
        val overdue = repo.createAssignment(
            title = "Overdue Math",
            subject = "Math",
            dueDate = LocalDate.now().minusDays(1),
            instructions = "Solve page 5",
            pageRef = "p. 5",
            teacherUserId = "teacher_1",
            studentIds = listOf("student_1"),
            branchId = "b1",
            isDraft = false
        )

        // Attempt 1: submitted (should be marked isLate = true)
        val sub1Result = repo.submitAssignment(
            assignmentId = overdue.id,
            studentId = "student_1",
            note = "Late solution attempt 1",
            files = listOf("uploads/math1.jpg"),
            clientRequestId = "req_1",
            branchId = "b1"
        )
        assertTrue(sub1Result.isSuccess)
        val sub1 = sub1Result.getOrThrow()
        assertEquals(1, sub1.attemptNumber)
        assertTrue("Overdue submission must be marked late", sub1.isLate)

        // Teacher reviews and requests revision
        val reviewResult = repo.reviewSubmission(
            submissionId = sub1.id,
            action = "request_revision",
            revisionNote = "Please re-do question 2"
        )
        assertTrue(reviewResult.isSuccess)
        val reviewedSub = reviewResult.getOrThrow()
        assertEquals("revision_requested", reviewedSub.status)
        assertEquals("Please re-do question 2", reviewedSub.revisionNote)

        // Attempt 2: student re-submits
        val sub2Result = repo.submitAssignment(
            assignmentId = overdue.id,
            studentId = "student_1",
            note = "Fixed question 2",
            files = listOf("uploads/math2.jpg"),
            clientRequestId = "req_2",
            branchId = "b1"
        )
        assertTrue(sub2Result.isSuccess)
        val sub2 = sub2Result.getOrThrow()
        assertEquals(2, sub2.attemptNumber)
        assertEquals(sub1.id, sub2.parentSubmissionId)

        // Teacher reviews and grades with EXCELLENT
        val gradeResult = repo.reviewSubmission(
            submissionId = sub2.id,
            action = "grade",
            gradeLevel = "EXCELLENT"
        )
        assertTrue(gradeResult.isSuccess)
        val gradedSub = gradeResult.getOrThrow()
        assertEquals("graded", gradedSub.status)
        assertEquals("EXCELLENT", gradedSub.gradeLevel)

        // Verify latest submission lookup
        val latest = repo.getLatestSubmission(overdue.id, "student_1")
        assertNotNull(latest)
        assertEquals(2, latest?.attemptNumber)
        assertEquals("graded", latest?.status)
    }

    @Test
    fun testRemindersAndSubmissionsList() = runTest {
        val assign = repo.createAssignment(
            title = "Science Project",
            subject = "Science",
            dueDate = LocalDate.now().plusDays(2),
            instructions = "Make volcano model",
            pageRef = null,
            teacherUserId = "teacher_1",
            studentIds = listOf("student_1"),
            isDraft = false
        )

        // Preview reminders
        val previewResult = repo.previewReminders(assign.id)
        assertTrue(previewResult.isSuccess)
        val preview = previewResult.getOrThrow()
        assertEquals(1, preview.count)

        // Send reminders
        val sendResult = repo.sendReminders(assign.id, kind = "pending")
        assertTrue(sendResult.isSuccess)
        val send = sendResult.getOrThrow()
        assertEquals(1, send.sent)

        // Get assignment submissions for teacher
        val subsResult = repo.getAssignmentSubmissions(assign.id)
        assertTrue(subsResult.isSuccess)
        val subs = subsResult.getOrThrow()
        assertEquals(1, subs.items.size)
        assertNotNull(subs.stats)
    }
}
