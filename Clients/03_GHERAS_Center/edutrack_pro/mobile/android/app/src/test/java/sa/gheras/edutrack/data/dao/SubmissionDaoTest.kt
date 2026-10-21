package sa.gheras.edutrack.data.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import sa.gheras.edutrack.data.db.GherasDatabase
import sa.gheras.edutrack.data.entity.AssignmentEntity
import sa.gheras.edutrack.data.entity.AssignmentStudentEntity
import sa.gheras.edutrack.data.entity.StudentEntity
import sa.gheras.edutrack.data.entity.SubmissionEntity
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class SubmissionDaoTest {

    private lateinit var db: GherasDatabase
    private lateinit var submissionDao: SubmissionDao
    private lateinit var assignmentDao: AssignmentDao
    private lateinit var studentDao: StudentDao
    private lateinit var assignmentStudentDao: AssignmentStudentDao

    private val now = Instant.parse("2026-10-21T10:00:00Z")

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, GherasDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        submissionDao = db.submissionDao()
        assignmentDao = db.assignmentDao()
        studentDao = db.studentDao()
        assignmentStudentDao = db.assignmentStudentDao()

        // Seed basic student and assignment
        val student = StudentEntity(
            id = "student_1",
            branchId = "b1",
            name = "Zaid",
            gender = "MALE",
            createdAt = now,
            updatedAt = now
        )
        val student2 = StudentEntity(
            id = "student_2",
            branchId = "b1",
            name = "Layla",
            gender = "FEMALE",
            createdAt = now,
            updatedAt = now
        )
        val assignment1 = AssignmentEntity(
            id = "asgn_1",
            branchId = "b1",
            title = "Math HW 1",
            subject = "Math",
            kind = "homework",
            dueDate = LocalDate.of(2026, 10, 25),
            teacherUserId = "teacher_1",
            instructions = "Solve pg 10",
            pageRef = "p10",
            isDraft = false,
            createdAt = now,
            updatedAt = now
        )
        val assignment2 = AssignmentEntity(
            id = "asgn_2",
            branchId = "b1",
            title = "Arabic HW 1",
            subject = "Arabic",
            kind = "homework",
            dueDate = LocalDate.of(2026, 10, 26),
            teacherUserId = "teacher_1",
            instructions = "Write essay",
            pageRef = "p15",
            isDraft = false,
            createdAt = now,
            updatedAt = now
        )

        studentDao.upsertAll(listOf(student, student2))
        assignmentDao.upsertAll(listOf(assignment1, assignment2))
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun testMultiAttempt_getLatestAndObserveAttempts() = runTest {
        // Attempt 1: submitted
        val sub1 = SubmissionEntity(
            id = "sub_1_att_1",
            branchId = "b1",
            assignmentId = "asgn_1",
            studentId = "student_1",
            attemptNumber = 1,
            status = "revision_requested",
            revisionNote = "Please fix question 3",
            submittedAt = now,
            createdAt = now,
            updatedAt = now
        )
        submissionDao.upsert(sub1)

        val latest1 = submissionDao.getLatestByAssignmentAndStudent("asgn_1", "student_1")
        assertNotNull(latest1)
        assertEquals(1, latest1?.attemptNumber)
        assertEquals("revision_requested", latest1?.status)
        assertEquals("Please fix question 3", latest1?.revisionNote)

        // Attempt 2: revised and graded
        val sub2 = SubmissionEntity(
            id = "sub_1_att_2",
            branchId = "b1",
            assignmentId = "asgn_1",
            studentId = "student_1",
            attemptNumber = 2,
            parentSubmissionId = "sub_1_att_1",
            status = "graded",
            grade = 98.0,
            gradeLevel = "EXCELLENT",
            feedback = "أحسنت ممتاز",
            submittedAt = now.plusSeconds(3600),
            createdAt = now.plusSeconds(3600),
            updatedAt = now.plusSeconds(3600)
        )
        submissionDao.upsert(sub2)

        val latest2 = submissionDao.getLatestByAssignmentAndStudent("asgn_1", "student_1")
        assertNotNull(latest2)
        assertEquals(2, latest2?.attemptNumber)
        assertEquals("graded", latest2?.status)
        assertEquals("EXCELLENT", latest2?.gradeLevel)
        assertEquals("sub_1_att_1", latest2?.parentSubmissionId)

        // Verify observeAttempts returns both in ascending attempt_number order
        submissionDao.observeAttempts("asgn_1", "student_1").test {
            val list = awaitItem()
            assertEquals(2, list.size)
            assertEquals(1, list[0].attemptNumber)
            assertEquals(2, list[1].attemptNumber)
        }

        // Verify specific attempt lookup
        val attempt1 = submissionDao.getByAttempt("asgn_1", "student_1", 1)
        assertEquals("sub_1_att_1", attempt1?.id)
        val attempt2 = submissionDao.getByAttempt("asgn_1", "student_1", 2)
        assertEquals("sub_1_att_2", attempt2?.id)
    }

    @Test
    fun testObserveLatestByStudent_returnsOnlyLatestAttemptPerAssignment() = runTest {
        // Student 1 has two attempts on asgn_1
        submissionDao.upsert(
            SubmissionEntity(
                id = "sub_a1_1",
                branchId = "b1",
                assignmentId = "asgn_1",
                studentId = "student_1",
                attemptNumber = 1,
                status = "revision_requested",
                submittedAt = now,
                createdAt = now,
                updatedAt = now
            )
        )
        submissionDao.upsert(
            SubmissionEntity(
                id = "sub_a1_2",
                branchId = "b1",
                assignmentId = "asgn_1",
                studentId = "student_1",
                attemptNumber = 2,
                status = "submitted",
                submittedAt = now.plusSeconds(1800),
                createdAt = now.plusSeconds(1800),
                updatedAt = now.plusSeconds(1800)
            )
        )

        // Student 1 has one attempt on asgn_2
        submissionDao.upsert(
            SubmissionEntity(
                id = "sub_a2_1",
                branchId = "b1",
                assignmentId = "asgn_2",
                studentId = "student_1",
                attemptNumber = 1,
                status = "graded",
                grade = 95.0,
                gradeLevel = "EXCELLENT",
                submittedAt = now.plusSeconds(3600),
                createdAt = now.plusSeconds(3600),
                updatedAt = now.plusSeconds(3600)
            )
        )

        submissionDao.observeLatestByStudent("student_1").test {
            val items = awaitItem()
            // Exactly 2 latest submissions (one for asgn_2, one for asgn_1)
            assertEquals(2, items.size)
            val asgn1Sub = items.find { it.assignmentId == "asgn_1" }
            val asgn2Sub = items.find { it.assignmentId == "asgn_2" }

            assertNotNull(asgn1Sub)
            assertEquals(2, asgn1Sub?.attemptNumber)
            assertEquals("submitted", asgn1Sub?.status)

            assertNotNull(asgn2Sub)
            assertEquals(1, asgn2Sub?.attemptNumber)
            assertEquals("graded", asgn2Sub?.status)
        }
    }

    @Test
    fun testObserveLatestByAssignment_teacherReviewList() = runTest {
        // Student 1: 2 attempts on asgn_1
        submissionDao.upsert(
            SubmissionEntity(
                id = "s1_att1",
                branchId = "b1",
                assignmentId = "asgn_1",
                studentId = "student_1",
                attemptNumber = 1,
                status = "revision_requested",
                submittedAt = now,
                createdAt = now,
                updatedAt = now
            )
        )
        submissionDao.upsert(
            SubmissionEntity(
                id = "s1_att2",
                branchId = "b1",
                assignmentId = "asgn_1",
                studentId = "student_1",
                attemptNumber = 2,
                status = "submitted",
                submittedAt = now.plusSeconds(100),
                createdAt = now.plusSeconds(100),
                updatedAt = now.plusSeconds(100)
            )
        )

        // Student 2: 1 attempt on asgn_1
        submissionDao.upsert(
            SubmissionEntity(
                id = "s2_att1",
                branchId = "b1",
                assignmentId = "asgn_1",
                studentId = "student_2",
                attemptNumber = 1,
                status = "graded",
                gradeLevel = "VERY_GOOD",
                submittedAt = now.plusSeconds(200),
                createdAt = now.plusSeconds(200),
                updatedAt = now.plusSeconds(200)
            )
        )

        submissionDao.observeLatestByAssignment("asgn_1").test {
            val list = awaitItem()
            assertEquals(2, list.size)
            val stud1Latest = list.find { it.studentId == "student_1" }
            val stud2Latest = list.find { it.studentId == "student_2" }

            assertEquals(2, stud1Latest?.attemptNumber)
            assertEquals("submitted", stud1Latest?.status)

            assertEquals(1, stud2Latest?.attemptNumber)
            assertEquals("graded", stud2Latest?.status)
        }
    }

    @Test
    fun testSoftDeletedAttempt_fallbackToPreviousAttempt() = runTest {
        submissionDao.upsert(
            SubmissionEntity(
                id = "sub_att_1",
                branchId = "b1",
                assignmentId = "asgn_1",
                studentId = "student_1",
                attemptNumber = 1,
                status = "revision_requested",
                submittedAt = now,
                createdAt = now,
                updatedAt = now
            )
        )
        submissionDao.upsert(
            SubmissionEntity(
                id = "sub_att_2",
                branchId = "b1",
                assignmentId = "asgn_1",
                studentId = "student_1",
                attemptNumber = 2,
                status = "submitted",
                submittedAt = now.plusSeconds(100),
                createdAt = now.plusSeconds(100),
                updatedAt = now.plusSeconds(100)
            )
        )

        // Mark attempt 2 deleted
        submissionDao.markDeleted("sub_att_2", now.plusSeconds(200), now.plusSeconds(200))

        val latest = submissionDao.getLatestByAssignmentAndStudent("asgn_1", "student_1")
        assertNotNull(latest)
        assertEquals(1, latest?.attemptNumber)
        assertEquals("revision_requested", latest?.status)
    }
}
