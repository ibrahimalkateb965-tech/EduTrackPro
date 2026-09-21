package sa.gheras.edutrack.data.repo

import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import sa.gheras.edutrack.data.dao.StudentAttendanceDao
import sa.gheras.edutrack.data.entity.PendingWriteEntity
import sa.gheras.edutrack.data.entity.StudentAttendanceEntity
import sa.gheras.edutrack.sync.Outbox
import java.time.LocalDate

class AttendanceRepository(
    private val attendanceDao: StudentAttendanceDao,
    private val outbox: Outbox
) {
    fun observeByStudent(studentId: String): Flow<List<StudentAttendanceEntity>> =
        attendanceDao.observeByStudent(studentId)

    fun observeByDate(date: LocalDate): Flow<List<StudentAttendanceEntity>> =
        attendanceDao.observeByDate(date)

    suspend fun recordAttendance(
        studentId: String,
        date: LocalDate,
        status: String,
        note: String? = null
    ) {
        val payload = JSONObject().apply {
            put("student_id", studentId)
            put("date", date.toString())
            put("status", status)
            if (note != null) put("note", note)
        }.toString()

        val naturalKey = "att:$studentId:$date"
        outbox.enqueue(
            kind = PendingWriteEntity.KIND_ATTENDANCE,
            naturalKey = naturalKey,
            payloadJson = payload
        )
    }

    suspend fun recordAttendanceBatch(
        date: LocalDate,
        records: List<AttendanceRecord>
    ) {
        records.forEach { record ->
            recordAttendance(record.studentId, date, record.status, record.note)
        }
    }
}

data class AttendanceRecord(
    val studentId: String,
    val status: String,
    val note: String? = null
)
