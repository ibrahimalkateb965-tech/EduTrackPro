package sa.gheras.edutrack.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import sa.gheras.edutrack.data.entity.AssignmentEntity
import sa.gheras.edutrack.data.entity.AssignmentStudentEntity
import sa.gheras.edutrack.data.entity.EvaluationEntity
import sa.gheras.edutrack.data.entity.InstallmentEntity
import sa.gheras.edutrack.data.entity.LessonLogEntity
import sa.gheras.edutrack.data.entity.NotificationEntity
import sa.gheras.edutrack.data.entity.PendingWriteEntity
import sa.gheras.edutrack.data.entity.ReceiptEntity
import sa.gheras.edutrack.data.entity.RoomEntity
import sa.gheras.edutrack.data.entity.ScheduleEntity
import sa.gheras.edutrack.data.entity.SkillProgressEntity
import sa.gheras.edutrack.data.entity.StudentAttendanceEntity
import sa.gheras.edutrack.data.entity.StudentEntity
import sa.gheras.edutrack.data.entity.SubmissionEntity
import sa.gheras.edutrack.data.entity.SubmissionFileEntity
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
class ReplaceScopeTest {

    private lateinit var db: GherasDatabase

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, GherasDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun closeDb() {
        db.close()
    }

    /** Raw row count — DAOs deliberately expose no unfiltered `count()`, so tests query directly. */
    private fun countRows(table: String): Int =
        db.query("SELECT COUNT(*) FROM $table", null).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private val now: Instant = Instant.parse("2026-09-21T08:00:00Z")
    private val today: LocalDate = LocalDate.parse("2026-09-21")
    private val timeStart: LocalTime = LocalTime.parse("09:00:00")
    private val timeEnd: LocalTime = LocalTime.parse("10:00:00")

    private fun createInitialPayload(): ScopePayload {
        val room = RoomEntity(
            id = "room_1",
            branchId = "branch_1",
            name = "Room A",
            groupName = "Group 1",
            createdAt = now,
            updatedAt = now
        )
        val schedule = ScheduleEntity(
            id = "sched_1",
            branchId = "branch_1",
            roomId = "room_1",
            teacherUserId = "teacher_1",
            day = "SUNDAY",
            startTime = timeStart,
            endTime = timeEnd,
            subject = "Tajweed",
            groupName = "Group 1",
            createdAt = now,
            updatedAt = now
        )
        val student = StudentEntity(
            id = "student_1",
            branchId = "branch_1",
            name = "Ahmad",
            nationalId = "1234567890",
            birthDate = today.minusYears(10),
            nationality = "SA",
            gender = "MALE",
            difficultyNotes = null,
            childNotes = null,
            fatherName = "Father",
            fatherPhone = "0500000001",
            motherName = "Mother",
            motherPhone = "0500000002",
            guardianPhone = null,
            guardianRelation = null,
            pickupType = null,
            pickupName = null,
            pickupRelation = null,
            pickupPhone = null,
            previousSchool = null,
            previousLevel = null,
            educationNotes = null,
            roomId = "room_1",
            groupName = "Group 1",
            createdAt = now,
            updatedAt = now
        )
        val installment = InstallmentEntity(
            id = "inst_1",
            branchId = "branch_1",
            studentId = "student_1",
            feePlanId = "plan_1",
            seqNo = 1,
            dueDate = today.plusDays(30),
            amount = 500.0,
            status = "PENDING",
            createdAt = now,
            updatedAt = now
        )
        val receipt = ReceiptEntity(
            id = "rcpt_1",
            branchId = "branch_1",
            paymentId = "pay_1",
            studentId = "student_1",
            installmentId = "inst_1",
            receiptNo = 1,
            amount = 500.0,
            method = "CASH",
            paidOn = today,
            issuedOn = now,
            createdAt = now,
            updatedAt = now
        )
        val attendance = StudentAttendanceEntity(
            id = "att_1",
            branchId = "branch_1",
            studentId = "student_1",
            date = today,
            status = "PRESENT",
            note = null,
            recordedByUserId = "teacher_1",
            createdAt = now,
            updatedAt = now
        )
        val evaluation = EvaluationEntity(
            id = "eval_1",
            branchId = "branch_1",
            studentId = "student_1",
            subject = "Tajweed",
            evalType = "daily",
            date = today,
            value = 95.0,
            teacherUserId = "teacher_1",
            createdAt = now,
            updatedAt = now
        )
        val skillProgress = SkillProgressEntity(
            id = "skill_1",
            branchId = "branch_1",
            studentId = "student_1",
            subject = "Quran",
            skill = "Memorization",
            level = "IN_PROGRESS",
            date = today,
            note = null,
            createdAt = now,
            updatedAt = now
        )
        val lessonLog = LessonLogEntity(
            id = "log_1",
            branchId = "branch_1",
            scheduleId = "sched_1",
            date = today,
            status = "DONE",
            covered = "Surah Al-Baqarah 1-10",
            homework = null,
            notes = null,
            teacherUserId = "teacher_1",
            createdAt = now,
            updatedAt = now
        )
        val assignment = AssignmentEntity(
            id = "asgn_1",
            branchId = "branch_1",
            title = "Homework 1",
            subject = "Tajweed",
            kind = "memorization",
            dueDate = today.plusDays(7),
            teacherUserId = "teacher_1",
            instructions = null,
            pageRef = null,
            createdAt = now,
            updatedAt = now
        )
        val assignmentStudent = AssignmentStudentEntity(
            id = "asgn_stud_1",
            branchId = "branch_1",
            assignmentId = "asgn_1",
            studentId = "student_1",
            createdAt = now,
            updatedAt = now
        )
        val submission = SubmissionEntity(
            id = "sub_1",
            branchId = "branch_1",
            assignmentId = "asgn_1",
            studentId = "student_1",
            submittedAt = now,
            status = "submitted",
            grade = null,
            feedback = null,
            createdAt = now,
            updatedAt = now
        )
        val submissionFile = SubmissionFileEntity(
            id = "file_1",
            branchId = "branch_1",
            submissionId = "sub_1",
            storageKey = "submissions/sub_1/audio.mp3",
            width = null,
            height = null,
            bytes = 1024L,
            sha256 = null,
            createdAt = now,
            updatedAt = now
        )
        val notification = NotificationEntity(
            id = "notif_1",
            branchId = "branch_1",
            userId = "teacher_1",
            kind = "info",
            title = "Welcome",
            body = "Welcome to EduTrack",
            readAt = null,
            sentAt = now,
            createdAt = now,
            updatedAt = now
        )

        return ScopePayload(
            rooms = listOf(room),
            schedules = listOf(schedule),
            students = listOf(student),
            installments = listOf(installment),
            receipts = listOf(receipt),
            attendance = listOf(attendance),
            evaluations = listOf(evaluation),
            skillProgress = listOf(skillProgress),
            lessonLogs = listOf(lessonLog),
            assignments = listOf(assignment),
            assignmentStudents = listOf(assignmentStudent),
            submissions = listOf(submission),
            submissionFiles = listOf(submissionFile),
            notifications = listOf(notification)
        )
    }

    @Test
    fun testReplaceAll_foreignKeyOrder_succeedsWithoutConstraintException() = runTest {
        // 1. Insert initial full scope
        val initialPayload = createInitialPayload()
        db.replaceAll(initialPayload)

        assertEquals(1, countRows("rooms"))
        assertEquals(1, countRows("students"))

        // 2. Prepare replacement scope with new room and student
        val room2 = RoomEntity(
            id = "room_2",
            branchId = "branch_1",
            name = "Room B",
            groupName = "Group 2",
            createdAt = now,
            updatedAt = now
        )
        val student2 = StudentEntity(
            id = "student_2",
            branchId = "branch_1",
            name = "Khalid",
            nationalId = "9876543210",
            birthDate = today.minusYears(11),
            nationality = "SA",
            gender = "MALE",
            difficultyNotes = null,
            childNotes = null,
            fatherName = "Father",
            fatherPhone = "0500000003",
            motherName = "Mother",
            motherPhone = "0500000004",
            guardianPhone = null,
            guardianRelation = null,
            pickupType = null,
            pickupName = null,
            pickupRelation = null,
            pickupPhone = null,
            previousSchool = null,
            previousLevel = null,
            educationNotes = null,
            roomId = "room_2",
            groupName = "Group 2",
            createdAt = now,
            updatedAt = now
        )

        val newPayload = ScopePayload(
            rooms = listOf(room2),
            students = listOf(student2)
        )

        // 3. replaceAll must clear children first, parents last, and insert parents first, children last.
        // If reverse dependency order was violated, SQLiteConstraintException would fail this test.
        db.replaceAll(newPayload)

        assertEquals("Old room_1 should be cleared and replaced with room_2", 1, countRows("rooms"))
        assertEquals("room_2", db.roomDao().getById("room_2")?.id)
        assertEquals(1, countRows("students"))
        assertEquals("student_2", db.studentDao().getById("student_2")?.id)
        assertEquals(0, countRows("receipts"))
        assertEquals(0, countRows("installments"))
        assertEquals(0, countRows("submission_files"))
    }

    @Test
    fun testTurbineEmission_singleEmissionOnReplaceScope() = runTest {
        val payload = createInitialPayload()
        db.replaceAll(payload)

        // Turbine collects DAO Flow
        db.studentDao().observeByRoom("room_1").test {
            val initialList = awaitItem()
            assertEquals(1, initialList.size)
            assertEquals("Ahmad", initialList[0].name)

            // Replace with updated student in same room. Must go through db.replaceAll — the
            // per-DAO StudentDao.replaceScope() would DELETE FROM students while installments /
            // receipts / attendance still hold RESTRICT FKs to it (production only ever calls
            // replaceAll, see PullSync). One transaction => one invalidation => one emission.
            val updatedStudent = payload.students[0].copy(name = "Ahmad Updated")
            db.replaceAll(payload.copy(students = listOf(updatedStudent)))

            val updatedList = awaitItem()
            assertEquals(1, updatedList.size)
            assertEquals("Ahmad Updated", updatedList[0].name)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun testPendingWrites_reapplyPendingInsideTransaction() = runTest {
        val payload = createInitialPayload()
        db.replaceAll(payload)

        // Insert pending write outbox row
        val pendingWrite = PendingWriteEntity(
            id = "pending_1",
            kind = PendingWriteEntity.KIND_ATTENDANCE,
            naturalKey = "att_1",
            payloadJson = "{\"status\":\"ABSENT\"}",
            createdAt = now
        )
        db.pendingWriteDao().upsert(pendingWrite)
        assertEquals(1, countRows("pending_writes"))

        // Execute replaceAll with a reapply callback
        var reapplyCalled = false
        db.replaceAll(payload) { pendingList ->
            reapplyCalled = true
            assertEquals(1, pendingList.size)
            assertEquals("pending_1", pendingList[0].id)
            // Reapply projection: update local attendance state to ABSENT
            val currentAtt = db.studentAttendanceDao().getById("att_1")
            if (currentAtt != null) {
                db.studentAttendanceDao().upsert(currentAtt.copy(status = "ABSENT"))
            }
        }

        assertTrue("reapplyPending callback must be executed", reapplyCalled)
        assertEquals("pending_writes row count must be unchanged by a pull", 1, countRows("pending_writes"))
        val att = db.studentAttendanceDao().getById("att_1")
        assertEquals("ABSENT", att?.status)
    }

    @Test
    fun testRollbackOnConstraintFailure() = runTest {
        val payload = createInitialPayload()
        db.replaceAll(payload)
        assertEquals(1, countRows("students"))

        // reapplyPending only runs when the outbox is non-empty — seed one row so the callback fires.
        db.pendingWriteDao().upsert(
            PendingWriteEntity(
                id = "pending_1",
                kind = PendingWriteEntity.KIND_ATTENDANCE,
                naturalKey = "att_1",
                payloadJson = "{}",
                createdAt = now
            )
        )

        // Replace with a *different* snapshot (empty scope) and throw inside the transaction.
        // If the transaction were not rolled back, students would be 0 afterwards.
        var threw = false
        try {
            db.replaceAll(ScopePayload()) {
                throw IllegalStateException("Simulated network/DB error during transaction")
            }
        } catch (e: IllegalStateException) {
            threw = true
        }

        assertTrue("Exception must be thrown", threw)
        // Ensure state was not corrupted or half-cleared
        assertEquals("Previous state must be preserved after rollback", 1, countRows("students"))
        assertEquals("Ahmad", db.studentDao().getById("student_1")?.name)
        assertEquals("Child rows must survive rollback too", 1, countRows("receipts"))
        assertEquals("Outbox must be untouched", 1, countRows("pending_writes"))
    }
}
