package sa.gheras.edutrack.data.repo

import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import sa.gheras.edutrack.data.dao.LessonLogDao
import sa.gheras.edutrack.data.entity.LessonLogEntity
import sa.gheras.edutrack.data.entity.PendingWriteEntity
import sa.gheras.edutrack.sync.Outbox
import java.time.LocalDate

class LessonLogsRepository(
    private val lessonLogDao: LessonLogDao,
    private val outbox: Outbox
) {
    fun observeBySchedule(scheduleId: String): Flow<List<LessonLogEntity>> =
        lessonLogDao.observeBySchedule(scheduleId)

    suspend fun getByScheduleAndDate(scheduleId: String, date: LocalDate): LessonLogEntity? =
        lessonLogDao.getByScheduleAndDate(scheduleId, date)

    suspend fun recordLessonLog(
        scheduleId: String,
        date: LocalDate,
        status: String,
        covered: String? = null,
        homework: String? = null,
        notes: String? = null
    ) {
        val payload = JSONObject().apply {
            put("schedule_id", scheduleId)
            put("date", date.toString())
            put("status", status)
            if (covered != null) put("covered", covered)
            if (homework != null) put("homework", homework)
            if (notes != null) put("notes", notes)
        }.toString()

        val naturalKey = "log:$scheduleId:$date"
        outbox.enqueue(
            kind = PendingWriteEntity.KIND_LESSON_LOG,
            naturalKey = naturalKey,
            payloadJson = payload
        )
    }
}
