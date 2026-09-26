package sa.gheras.edutrack.sync

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
import sa.gheras.edutrack.data.entity.EvaluationEntity
import sa.gheras.edutrack.data.entity.PendingWriteEntity
import sa.gheras.edutrack.data.entity.RoomEntity
import sa.gheras.edutrack.data.entity.StudentEntity
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class OutboxTest {

    private lateinit var db: GherasDatabase
    private lateinit var outbox: Outbox

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, GherasDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        outbox = Outbox(context, db) { "teacher_1" }
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** Raw row count — `PendingWriteDao` only exposes a filtered `countPending()` Flow. */
    private fun countRows(table: String): Int =
        db.query("SELECT COUNT(*) FROM $table", null).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    /**
     * `student_attendance.student_id` is a RESTRICT foreign key, so the optimistic projection
     * needs the parent room + student rows to exist before [Outbox.enqueue] runs.
     */
    private suspend fun seedStudent(studentId: String) {
        val now = Instant.now()
        db.roomDao().upsert(RoomEntity("room_1", null, "Room 1", "Group 1", now, now))
        db.studentDao().upsert(
            StudentEntity(
                id = studentId,
                branchId = null,
                name = "Ahmad",
                nationalId = null,
                birthDate = LocalDate.now().minusYears(10),
                nationality = "SA",
                difficultyNotes = null,
                childNotes = null,
                fatherName = null,
                fatherPhone = null,
                motherName = null,
                motherPhone = null,
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
        )
    }

    @Test
    fun testEnqueue_appliesOptimisticProjectionAndInsertsPendingRow() = runTest {
        seedStudent("student_1")

        val payload = "{\"student_id\":\"student_1\",\"date\":\"2026-09-21\",\"status\":\"PRESENT\",\"note\":\"Good\"}"
        outbox.enqueue(
            kind = PendingWriteEntity.KIND_ATTENDANCE,
            naturalKey = "att:student_1:2026-09-21",
            payloadJson = payload
        )

        // 1. Pending write row inserted
        assertEquals(1, countRows("pending_writes"))
        val pending = db.pendingWriteDao().getByNaturalKey("att:student_1:2026-09-21")
        assertNotNull(pending)
        assertEquals(PendingWriteEntity.KIND_ATTENDANCE, pending?.kind)

        // 2. Optimistic projection present locally in student_attendance table
        val att = db.studentAttendanceDao().getById("local:student_1:2026-09-21")
        assertNotNull(att)
        assertEquals("PRESENT", att?.status)
        assertEquals("teacher_1", att?.recordedByUserId)
    }

    @Test
    fun testEnqueue_coalescing_sameNaturalKeyReplacesPendingRow() = runTest {
        seedStudent("student_1")
        val payload1 = "{\"student_id\":\"student_1\",\"date\":\"2026-09-21\",\"status\":\"PRESENT\"}"
        val payload2 = "{\"student_id\":\"student_1\",\"date\":\"2026-09-21\",\"status\":\"ABSENT\"}"

        outbox.enqueue(PendingWriteEntity.KIND_ATTENDANCE, "att:student_1:2026-09-21", payload1)
        assertEquals(1, countRows("pending_writes"))

        outbox.enqueue(PendingWriteEntity.KIND_ATTENDANCE, "att:student_1:2026-09-21", payload2)
        // Count should still be 1 due to coalescing
        assertEquals(1, countRows("pending_writes"))

        val pending = db.pendingWriteDao().getByNaturalKey("att:student_1:2026-09-21")
        assertEquals(payload2, pending?.payloadJson)
    }

    @Test
    fun testPendingCount_flowEmitsCorrectValue() = runTest {
        seedStudent("s1")
        db.pendingWriteDao().countPending().test {
            assertEquals(0, awaitItem())

            outbox.enqueue(
                PendingWriteEntity.KIND_ATTENDANCE,
                "att:1",
                "{\"student_id\":\"s1\",\"date\":\"2026-09-21\",\"status\":\"PRESENT\"}"
            )
            assertEquals(1, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun testEnqueue_dailyEval_optimisticProjection_and_deduplication() = runTest {
        seedStudent("student_1")

        // 1. Initial evaluation enqueue
        val payload1 = "{\"student_id\":\"student_1\",\"date\":\"2026-09-21\",\"subject\":\"القرآن\",\"score\":10.0}"
        outbox.enqueue(
            kind = PendingWriteEntity.KIND_DAILY_EVAL,
            naturalKey = "eval:student_1:2026-09-21:القرآن",
            payloadJson = payload1
        )

        assertEquals(1, countRows("evaluations"))
        val eval1 = db.evaluationDao().getById("local:student_1:2026-09-21:القرآن")
        assertNotNull(eval1)
        assertEquals(10.0, eval1!!.value, 0.001)

        // 2. Updated evaluation for same student, date, and subject
        val payload2 = "{\"student_id\":\"student_1\",\"date\":\"2026-09-21\",\"subject\":\"القرآن\",\"score\":7.5}"
        outbox.enqueue(
            kind = PendingWriteEntity.KIND_DAILY_EVAL,
            naturalKey = "eval:student_1:2026-09-21:القرآن",
            payloadJson = payload2
        )

        // Dedup invariant: exactly 1 row must exist, updated to 7.5
        assertEquals(1, countRows("evaluations"))
        val eval2 = db.evaluationDao().getById("local:student_1:2026-09-21:القرآن")
        assertNotNull(eval2)
        assertEquals(7.5, eval2!!.value, 0.001)
    }

    @Test
    fun testRevertOptimisticProjection_restoresDisplacedServerEvaluation() = runTest {
        seedStudent("student_1")

        // 1. Seed existing server evaluation
        val serverEval = EvaluationEntity(
            id = "server_eval_123",
            branchId = "branch_1",
            studentId = "student_1",
            subject = "القرآن",
            evalType = "daily",
            date = LocalDate.parse("2026-09-21"),
            value = 10.0,
            teacherUserId = "teacher_1",
            createdAt = Instant.parse("2026-09-21T08:00:00Z"),
            updatedAt = Instant.parse("2026-09-21T08:00:00Z")
        )
        db.evaluationDao().upsert(serverEval)
        assertEquals(1, countRows("evaluations"))

        // 2. Teacher updates evaluation optimistically to 7.5
        val payload = "{\"student_id\":\"student_1\",\"date\":\"2026-09-21\",\"subject\":\"القرآن\",\"score\":7.5}"
        outbox.enqueue(
            kind = PendingWriteEntity.KIND_DAILY_EVAL,
            naturalKey = "eval:student_1:2026-09-21:القرآن",
            payloadJson = payload
        )

        // Local row replaced the server row via unique index
        assertEquals(1, countRows("evaluations"))
        val localEval = db.evaluationDao().getById("local:student_1:2026-09-21:القرآن")
        assertNotNull(localEval)
        assertEquals(7.5, localEval!!.value, 0.001)

        val pending = db.pendingWriteDao().getByNaturalKey("eval:student_1:2026-09-21:القرآن")
        assertNotNull(pending)

        // 3. Simulate permanent failure (e.g. 422 validation error on server): revert is triggered
        OutboxWorker.revertOptimisticProjection(pending!!, db)

        // Invariant: local row is removed and original server row is restored with its original ID and score!
        assertNull(db.evaluationDao().getById("local:student_1:2026-09-21:القرآن"))
        assertEquals(1, countRows("evaluations"))
        val restored = db.evaluationDao().getById("server_eval_123")
        assertNotNull("Original server evaluation must be restored", restored)
        assertEquals(10.0, restored!!.value, 0.001)
    }

    @Test
    fun testRevertOptimisticProjection_freshEvaluation_deletesLocalRow() = runTest {
        seedStudent("student_1")

        val payload = "{\"student_id\":\"student_1\",\"date\":\"2026-09-21\",\"subject\":\"القرآن\",\"score\":8.0}"
        outbox.enqueue(
            kind = PendingWriteEntity.KIND_DAILY_EVAL,
            naturalKey = "eval:student_1:2026-09-21:القرآن",
            payloadJson = payload
        )

        assertEquals(1, countRows("evaluations"))
        val pending = db.pendingWriteDao().getByNaturalKey("eval:student_1:2026-09-21:القرآن")
        assertNotNull(pending)

        // Revert fresh evaluation
        OutboxWorker.revertOptimisticProjection(pending!!, db)

        // Table should be empty again
        assertEquals(0, countRows("evaluations"))
        assertNull(db.evaluationDao().getById("local:student_1:2026-09-21:القرآن"))
    }
}
