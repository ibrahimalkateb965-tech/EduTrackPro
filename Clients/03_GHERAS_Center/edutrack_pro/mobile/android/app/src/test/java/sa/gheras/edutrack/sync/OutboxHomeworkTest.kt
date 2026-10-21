package sa.gheras.edutrack.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import sa.gheras.edutrack.data.db.GherasDatabase
import sa.gheras.edutrack.data.entity.AssignmentEntity
import sa.gheras.edutrack.data.entity.PendingWriteEntity
import sa.gheras.edutrack.data.entity.StudentEntity
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class OutboxHomeworkTest {

    private lateinit var db: GherasDatabase
    private lateinit var outbox: Outbox
    private val now = Instant.parse("2026-10-21T10:00:00Z")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, GherasDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        outbox = Outbox(context, db) { "guardian_user_1" }

        // Seed student and assignment
        db.studentDao().upsert(
            StudentEntity(
                id = "student_1",
                branchId = "b1",
                name = "Omar",
                gender = "MALE",
                createdAt = now,
                updatedAt = now
            )
        )
        db.assignmentDao().upsert(
            AssignmentEntity(
                id = "asgn_1",
                branchId = "b1",
                title = "Math 101",
                subject = "Math",
                kind = "homework",
                dueDate = LocalDate.of(2026, 10, 25),
                teacherUserId = "teacher_1",
                instructions = "Solve 1 to 5",
                pageRef = null,
                isDraft = false,
                createdAt = now,
                updatedAt = now
            )
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testOfflineSubmission_optimisticProjectionAndOutboxEnqueue() = runTest {
        val payload = JSONObject().apply {
            put("submission_id", "local_sub_1")
            put("assignment_id", "asgn_1")
            put("student_id", "student_1")
            put("attempt_number", 1)
            put("is_late", false)
            put("note", "حل الواجب كاملاً")
            val filesArr = JSONArray()
            filesArr.put("uploads/photo1.jpg")
            put("files", filesArr)
            put("client_request_id", "req_uuid_1")
        }.toString()

        outbox.enqueue(
            kind = PendingWriteEntity.KIND_SUBMISSION_CREATE,
            naturalKey = "submission:asgn_1:student_1:req_uuid_1",
            payloadJson = payload
        )

        // Verify row in pending_writes
        val pending = db.pendingWriteDao().listPending()
        assertEquals(1, pending.size)
        assertEquals(PendingWriteEntity.KIND_SUBMISSION_CREATE, pending[0].kind)

        // Verify optimistic projection created local submission in Room
        val sub = db.submissionDao().getById("local_sub_1")
        assertNotNull(sub)
        assertEquals("asgn_1", sub?.assignmentId)
        assertEquals("student_1", sub?.studentId)
        assertEquals(1, sub?.attemptNumber)
        assertEquals("submitted", sub?.status)
        assertEquals("حل الواجب كاملاً", sub?.feedback)

        // Verify file link created
        val files = db.submissionFileDao().getBySubmission("local_sub_1")
        assertEquals(1, files.size)
        assertEquals("uploads/photo1.jpg", files[0].storageKey)
    }

    @Test
    fun testRevertOptimisticProjection_deletesLocalSubmission() = runTest {
        val payload = JSONObject().apply {
            put("submission_id", "local_fail_sub")
            put("assignment_id", "asgn_1")
            put("student_id", "student_1")
            put("attempt_number", 2)
            put("is_late", true)
        }.toString()

        val pendingItem = PendingWriteEntity(
            id = "pending_1",
            kind = PendingWriteEntity.KIND_SUBMISSION_CREATE,
            naturalKey = "submission:asgn_1:student_1:req_fail",
            payloadJson = payload,
            createdAt = now
        )

        // Apply projection first
        outbox.reapplyPending(listOf(pendingItem))
        assertNotNull(db.submissionDao().getById("local_fail_sub"))

        // Now revert projection (as OutboxWorker does on terminal failure)
        OutboxWorker.revertOptimisticProjection(pendingItem, db)
        assertNull(db.submissionDao().getById("local_fail_sub"))
    }
}
