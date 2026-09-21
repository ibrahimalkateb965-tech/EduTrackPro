package sa.gheras.edutrack.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import sa.gheras.edutrack.data.db.GherasDatabase
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
}
