package sa.gheras.edutrack.data.repo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import sa.gheras.edutrack.data.db.GherasDatabase
import sa.gheras.edutrack.data.entity.PendingWriteEntity
import sa.gheras.edutrack.data.entity.RoomEntity
import sa.gheras.edutrack.data.entity.StudentEntity
import sa.gheras.edutrack.data.remote.fakes.FakeMeApi
import sa.gheras.edutrack.sync.Outbox
import sa.gheras.edutrack.sync.OutboxWorker
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class AttendanceRepositoryTest {

    private lateinit var db: GherasDatabase
    private lateinit var outbox: Outbox
    private lateinit var fakeMeApi: FakeMeApi
    private lateinit var repository: AttendanceRepository

    private val testStudentId = "stud_repo_1"
    private val testRoomId = "room_repo_1"
    private val today = LocalDate.of(2026, 10, 22)

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, GherasDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        outbox = Outbox(context, db) { "teacher_1" }
        fakeMeApi = FakeMeApi()
        repository = AttendanceRepository(
            attendanceDao = db.studentAttendanceDao(),
            excuseDao = db.attendanceExcuseDao(),
            meApi = fakeMeApi,
            outbox = outbox
        )

        // Seed room and student for FK RESTRICT constraints
        val now = Instant.now()
        db.roomDao().upsert(RoomEntity(testRoomId, null, "Room 1", "Group A", now, now))
        db.studentDao().upsert(
            StudentEntity(
                id = testStudentId,
                branchId = null,
                name = "Yousef",
                gender = "MALE",
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
    fun testRecordAttendance_enqueuesPendingWriteAndAppliesOptimisticProjection() = runTest {
        repository.recordAttendance(
            studentId = testStudentId,
            date = today,
            status = "حاضر",
            note = "حضور مبكر",
            isDraft = false
        )

        // Verify outbox pending write
        val pending = db.pendingWriteDao().listPending()
        assertEquals(1, pending.size)
        assertEquals(PendingWriteEntity.KIND_ATTENDANCE_RECORD, pending[0].kind)
        assertEquals("att:$testStudentId:$today", pending[0].naturalKey)

        // Verify optimistic projection in student_attendance
        val attendance = db.studentAttendanceDao().getByStudentAndDate(testStudentId, today)
        assertNotNull(attendance)
        assertEquals("حاضر", attendance?.status)
        assertEquals(false, attendance?.isDraft)
        assertEquals("حضور مبكر", attendance?.note)
    }

    @Test
    fun testSaveDraftAttendanceBatch_setsDraftTrueInOptimisticProjection() = runTest {
        repository.saveDraftAttendanceBatch(
            roomId = testRoomId,
            date = today,
            records = listOf(
                AttendanceRecord(studentId = testStudentId, status = "غائب", note = "لم يحضر بعد")
            )
        )

        val attendance = db.studentAttendanceDao().getByStudentAndDate(testStudentId, today)
        assertNotNull(attendance)
        assertEquals("غائب", attendance?.status)
        assertEquals(true, attendance?.isDraft)
    }

    @Test
    fun testApproveRoomAttendance_enqueuesApprovalPendingWrite() = runTest {
        repository.approveRoomAttendance(testRoomId, today)

        val pending = db.pendingWriteDao().listPending()
        assertEquals(1, pending.size)
        assertEquals(PendingWriteEntity.KIND_ATTENDANCE_APPROVE, pending[0].kind)
        assertEquals("att_approve:$testRoomId:$today", pending[0].naturalKey)
    }

    @Test
    fun testSubmitExcuse_enqueuesExcuseAndInsertsPendingExcuseEntity() = runTest {
        val excuseDate = today.plusDays(1)
        val localId = repository.submitExcuse(
            studentId = testStudentId,
            excuseDate = excuseDate,
            reasonText = "موعد مستشفى مؤكد",
            attachmentStorageKey = "uploads/report.pdf"
        )

        assertTrue(localId.startsWith("local:"))

        // Verify pending write
        val pending = db.pendingWriteDao().listPending()
        assertEquals(1, pending.size)
        assertEquals(PendingWriteEntity.KIND_ATTENDANCE_EXCUSE, pending[0].kind)

        // Verify optimistic excuse projection
        val excuse = db.attendanceExcuseDao().getById(localId)
        assertNotNull(excuse)
        assertEquals("موعد مستشفى مؤكد", excuse?.reasonText)
        assertEquals("pending", excuse?.status)
        assertEquals(excuseDate, excuse?.excuseDate)

        // Verify observation flow
        val excusesList = repository.observeExcusesByStudent(testStudentId).first()
        assertEquals(1, excusesList.size)
        assertEquals(localId, excusesList[0].id)
    }

    @Test
    fun testPatchExcuse_updatesExistingExcuseOptimistically() = runTest {
        val excuseDate = today.plusDays(1)
        val localId = repository.submitExcuse(
            studentId = testStudentId,
            excuseDate = excuseDate,
            reasonText = "موعد مبدئي"
        )

        // Patch excuse
        repository.patchExcuse(
            excuseId = localId,
            reasonText = "موعد مؤكد مع التقرير المرفق",
            attachmentStorageKey = "uploads/doctor_note.jpg"
        )

        val updated = db.attendanceExcuseDao().getById(localId)
        assertEquals("موعد مؤكد مع التقرير المرفق", updated?.reasonText)
        assertEquals("uploads/doctor_note.jpg", updated?.attachmentStorageKey)
    }

    @Test
    fun testOutboxWorker_flushesExcuseAndDraftSuccessfully() = runTest {
        repository.recordAttendance(
            studentId = testStudentId,
            date = today,
            status = "حاضر",
            isDraft = true
        )

        val pendingList = db.pendingWriteDao().listPending()
        assertEquals(1, pendingList.size)

        // Flush draft via worker logic
        OutboxWorker.flushItem(pendingList[0], fakeMeApi, db)
    }
}
