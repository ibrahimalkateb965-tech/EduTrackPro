package sa.gheras.edutrack.data.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import sa.gheras.edutrack.data.dao.AttendanceExcuseDao
import sa.gheras.edutrack.data.dao.StudentAttendanceDao
import sa.gheras.edutrack.data.entity.AttendanceExcuseEntity
import sa.gheras.edutrack.data.entity.PendingWriteEntity
import sa.gheras.edutrack.data.entity.StudentAttendanceEntity
import sa.gheras.edutrack.data.remote.MeApi
import sa.gheras.edutrack.data.remote.dto.AttendanceExcuseDto
import sa.gheras.edutrack.data.remote.dto.ReviewExcuseRequest
import sa.gheras.edutrack.sync.Outbox
import java.time.LocalDate
import java.util.UUID

class AttendanceRepository(
    private val attendanceDao: StudentAttendanceDao,
    private val excuseDao: AttendanceExcuseDao? = null,
    private val meApi: MeApi? = null,
    private val outbox: Outbox
) {
    fun observeByStudent(studentId: String): Flow<List<StudentAttendanceEntity>> =
        attendanceDao.observeByStudent(studentId)

    fun observeByDate(date: LocalDate): Flow<List<StudentAttendanceEntity>> =
        attendanceDao.observeByDate(date)

    fun observeByStudentBetween(studentId: String, from: LocalDate, to: LocalDate): Flow<List<StudentAttendanceEntity>> =
        attendanceDao.observeByStudentBetween(studentId, from, to)

    fun observeExcusesByStudent(studentId: String): Flow<List<AttendanceExcuseEntity>> =
        excuseDao?.observeByStudent(studentId) ?: emptyFlow()

    fun observeExcusesByStudentAndDate(studentId: String, date: LocalDate): Flow<AttendanceExcuseEntity?> =
        excuseDao?.observeByStudentAndDate(studentId, date) ?: emptyFlow()

    suspend fun recordAttendance(
        studentId: String,
        date: LocalDate,
        status: String,
        note: String? = null,
        isDraft: Boolean = false
    ) {
        val payload = JSONObject().apply {
            put("student_id", studentId)
            put("date", date.toString())
            put("status", status)
            put("is_draft", isDraft)
            if (note != null) put("note", note)
        }.toString()

        val naturalKey = "att:$studentId:$date"
        outbox.enqueue(
            kind = PendingWriteEntity.KIND_ATTENDANCE_RECORD,
            naturalKey = naturalKey,
            payloadJson = payload
        )
    }

    suspend fun recordAttendanceBatch(
        date: LocalDate,
        records: List<AttendanceRecord>,
        isDraft: Boolean = false
    ) {
        records.forEach { record ->
            recordAttendance(record.studentId, date, record.status, record.note, isDraft)
        }
    }

    suspend fun saveDraftAttendanceBatch(
        roomId: String,
        date: LocalDate,
        records: List<AttendanceRecord>
    ) {
        recordAttendanceBatch(date, records, isDraft = true)
    }

    suspend fun approveRoomAttendance(
        roomId: String,
        date: LocalDate
    ) {
        val payload = JSONObject().apply {
            put("room_id", roomId)
            put("date", date.toString())
        }.toString()

        val naturalKey = "att_approve:$roomId:$date"
        outbox.enqueue(
            kind = PendingWriteEntity.KIND_ATTENDANCE_APPROVE,
            naturalKey = naturalKey,
            payloadJson = payload
        )
    }

    suspend fun submitExcuse(
        studentId: String,
        excuseDate: LocalDate,
        reasonText: String,
        attachmentStorageKey: String? = null,
        localFilePath: String? = null
    ): String {
        val clientRequestId = UUID.randomUUID().toString()
        val localExcuseId = "local:$clientRequestId"

        val payload = JSONObject().apply {
            put("id", localExcuseId)
            put("student_id", studentId)
            put("excuse_date", excuseDate.toString())
            put("reason_text", reasonText)
            put("client_request_id", clientRequestId)
            if (attachmentStorageKey != null) put("attachment_storage_key", attachmentStorageKey)
            if (localFilePath != null) put("local_file_path", localFilePath)
        }.toString()

        val naturalKey = "att_excuse:$studentId:$excuseDate"
        outbox.enqueue(
            kind = PendingWriteEntity.KIND_ATTENDANCE_EXCUSE,
            naturalKey = naturalKey,
            payloadJson = payload
        )
        return localExcuseId
    }

    suspend fun patchExcuse(
        excuseId: String,
        reasonText: String? = null,
        attachmentStorageKey: String? = null,
        localFilePath: String? = null
    ) {
        val payload = JSONObject().apply {
            put("id", excuseId)
            if (reasonText != null) put("reason_text", reasonText)
            if (attachmentStorageKey != null) put("attachment_storage_key", attachmentStorageKey)
            if (localFilePath != null) put("local_file_path", localFilePath)
        }.toString()

        val naturalKey = "att_excuse_patch:$excuseId"
        outbox.enqueue(
            kind = PendingWriteEntity.KIND_ATTENDANCE_EXCUSE_PATCH,
            naturalKey = naturalKey,
            payloadJson = payload
        )
    }

    suspend fun listStaffExcuses(
        status: String? = null,
        roomId: String? = null,
        dateFrom: LocalDate? = null,
        dateTo: LocalDate? = null,
        studentId: String? = null
    ): List<AttendanceExcuseDto> = withContext(Dispatchers.IO) {
        val api = meApi ?: return@withContext emptyList()
        val response = api.listExcusesStaff(
            status = status,
            roomId = roomId,
            dateFrom = dateFrom?.toString(),
            dateTo = dateTo?.toString(),
            studentId = studentId
        )
        response.items
    }

    suspend fun reviewExcuse(
        excuseId: String,
        action: String,
        notes: String? = null
    ): AttendanceExcuseDto = withContext(Dispatchers.IO) {
        val api = checkNotNull(meApi) { "MeApi must not be null for supervisor review" }
        api.reviewExcuse(excuseId, ReviewExcuseRequest(action = action, supervisorNotes = notes))
    }
}

data class AttendanceRecord(
    val studentId: String,
    val status: String,
    val note: String? = null
)
