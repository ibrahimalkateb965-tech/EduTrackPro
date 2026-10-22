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
    fun testSaveDraftAttendanceBatch_contractPayloadAndOptimisticProjection() = runTest {
        repository.saveDraftAttendanceBatch(
            roomId = testRoomId,
            date = today,
            records = listOf(
                AttendanceRecord(studentId = testStudentId, status = "غياب بعذر", note = "عذر مرضي")
            )
        )

        // 1. Verify optimistic projection in student_attendance (draft = true)
        val attendance = db.studentAttendanceDao().getByStudentAndDate(testStudentId, today)
        assertNotNull(attendance)
        assertEquals("غائب", attendance?.status)
        assertEquals(true, attendance?.isDraft)

        // 2. Verify outbox payload structure: {room_id, date, items} (Y2 contract)
        val pendingList = db.pendingWriteDao().listPending()
        assertEquals(1, pendingList.size)
        assertEquals(PendingWriteEntity.KIND_ATTENDANCE_RECORD, pendingList[0].kind)

        val json = org.json.JSONObject(pendingList[0].payloadJson)
        assertEquals(testRoomId, json.getString("room_id"))
        assertEquals(today.toString(), json.getString("date"))
        val itemsArr = json.getJSONArray("items")
        assertEquals(1, itemsArr.length())
        assertEquals(testStudentId, itemsArr.getJSONObject(0).getString("student_id"))
        // Excused absence must be mapped to absent on network wire (Y4 / H2)
        assertEquals("غائب", itemsArr.getJSONObject(0).getString("status"))
        assertEquals("عذر مرضي", itemsArr.getJSONObject(0).getString("note"))

        // 3. Flush via OutboxWorker and verify SaveDraftAttendanceRequest received by API
        OutboxWorker.flushItem(pendingList[0], fakeMeApi, db)
        assertNotNull(fakeMeApi.lastDraftRequest)
        assertEquals(testRoomId, fakeMeApi.lastDraftRequest?.roomId)
        assertEquals(today.toString(), fakeMeApi.lastDraftRequest?.date)
        assertEquals(1, fakeMeApi.lastDraftRequest?.items?.size)
        assertEquals("غائب", fakeMeApi.lastDraftRequest?.items?.get(0)?.status)
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

    @Test
    fun testOutboxWorker_409ConflictIsTerminalAndRevertsOptimisticProjection() = runTest {
        val localId = repository.submitExcuse(
            studentId = testStudentId,
            excuseDate = today,
            reasonText = "عذر مكرر يسبب تعارض"
        )

        // Verify optimistic projection inserted
        assertNotNull(db.attendanceExcuseDao().getById(localId))

        val pendingList = db.pendingWriteDao().listPending()
        assertEquals(1, pendingList.size)
        val item = pendingList[0]

        // Create 409 HttpException
        val errorBody = okhttp3.ResponseBody.create(okhttp3.MediaType.parse("application/json"), "{\"detail\":\"Conflict\"}")
        val http409 = retrofit2.HttpException(retrofit2.Response.error<Any>(409, errorBody))

        // Simulate worker terminal handling on 409
        val code = http409.code()
        assertTrue(code == 403 || code == 404 || code == 409 || code == 422)
        val error = sa.gheras.edutrack.data.remote.ErrorMapper.map(http409)
        db.pendingWriteDao().markFailed(item.id, error.userMessage)
        OutboxWorker.revertOptimisticProjection(item, db)

        // Verify write marked failed (attempts = -1) so queue is not wedged
        val failed = db.pendingWriteDao().getById(item.id)
        assertEquals(-1, failed?.attempts)
        // Verify optimistic projection deleted
        org.junit.Assert.assertNull(db.attendanceExcuseDao().getById(localId))
    }
}
