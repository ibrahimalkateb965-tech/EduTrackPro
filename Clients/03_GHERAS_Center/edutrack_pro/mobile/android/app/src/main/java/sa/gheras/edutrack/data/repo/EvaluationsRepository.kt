package sa.gheras.edutrack.data.repo

import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import sa.gheras.edutrack.data.dao.EvaluationDao
import sa.gheras.edutrack.data.entity.EvaluationEntity
import sa.gheras.edutrack.data.entity.PendingWriteEntity
import sa.gheras.edutrack.sync.Outbox
import java.time.LocalDate

class EvaluationsRepository(
    private val evaluationDao: EvaluationDao,
    private val outbox: Outbox
) {
    fun observeByStudent(studentId: String): Flow<List<EvaluationEntity>> =
        evaluationDao.observeByStudent(studentId)

    fun observeByDate(date: LocalDate): Flow<List<EvaluationEntity>> =
        evaluationDao.observeBetween(date, date)

    suspend fun recordEvaluation(
        studentId: String,
        date: LocalDate,
        subject: String,
        score: Double,
        notes: String? = null
    ) {
        val payload = JSONObject().apply {
            put("student_id", studentId)
            put("date", date.toString())
            put("subject", subject)
            put("score", score)
            if (notes != null) put("notes", notes)
        }.toString()

        val naturalKey = "eval:$studentId:$date:$subject"
        outbox.enqueue(
            kind = PendingWriteEntity.KIND_DAILY_EVAL,
            naturalKey = naturalKey,
            payloadJson = payload
        )
    }

    suspend fun recordEvaluationBatch(
        date: LocalDate,
        subject: String,
        records: List<EvaluationRecord>
    ) {
        records.forEach { record ->
            recordEvaluation(record.studentId, date, subject, record.score, record.notes)
        }
    }
}

data class EvaluationRecord(
    val studentId: String,
    val score: Double,
    val notes: String? = null
)
