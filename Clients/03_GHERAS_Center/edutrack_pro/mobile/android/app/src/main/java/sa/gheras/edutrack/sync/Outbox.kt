package sa.gheras.edutrack.sync

import android.content.Context
import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONObject
import sa.gheras.edutrack.data.db.GherasDatabase
import sa.gheras.edutrack.data.entity.EvaluationEntity
import sa.gheras.edutrack.data.entity.LessonLogEntity
import sa.gheras.edutrack.data.entity.PendingWriteEntity
import sa.gheras.edutrack.data.entity.StudentAttendanceEntity
import sa.gheras.edutrack.data.repo.mappers.DateParsers
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class Outbox(
    private val context: Context,
    private val db: GherasDatabase,
    private val currentUserIdProvider: () -> String = { "me" }
) {

    suspend fun enqueue(kind: String, naturalKey: String, payloadJson: String) {
        db.withTransaction {
            val now = Instant.now()
            val existing = db.pendingWriteDao().getByNaturalKey(naturalKey)
            val id = existing?.id ?: UUID.randomUUID().toString()

            val writeEntity = PendingWriteEntity(
                id = id,
                kind = kind,
                naturalKey = naturalKey,
                payloadJson = payloadJson,
                createdAt = now,
                attempts = 0,
                lastError = null
            )
            db.pendingWriteDao().upsert(writeEntity)

            // Apply optimistic projection locally
            applyOptimisticProjection(kind, naturalKey, payloadJson, now)
        }

        scheduleFlush()
    }

    private suspend fun applyOptimisticProjection(
        kind: String,
        naturalKey: String,
        payloadJson: String,
        now: Instant
    ) {
        val json = JSONObject(payloadJson)
        val me = currentUserIdProvider()

        when (kind) {
            PendingWriteEntity.KIND_ATTENDANCE -> {
                val studentId = json.optString("student_id")
                val dateStr = json.optString("date")
                val status = json.optString("status")
                val note = json.optString("note", null)
                val date = DateParsers.parseLocalDate(dateStr) ?: LocalDate.now()

                val entity = StudentAttendanceEntity(
                    id = "local:$studentId:$dateStr",
                    branchId = null,
                    studentId = studentId,
                    date = date,
                    status = status,
                    note = note,
                    recordedByUserId = me,
                    createdAt = now,
                    updatedAt = now,
                    deletedAt = null
                )
                db.studentAttendanceDao().upsert(entity)
            }
            PendingWriteEntity.KIND_DAILY_EVAL -> {
                val studentId = json.optString("student_id")
                val dateStr = json.optString("date")
                val subject = json.optString("subject", "عام")
                val score = json.optDouble("score", 0.0)
                val date = DateParsers.parseLocalDate(dateStr) ?: LocalDate.now()

                val entity = EvaluationEntity(
                    id = "local:$studentId:$dateStr:$subject",
                    branchId = null,
                    studentId = studentId,
                    subject = subject,
                    evalType = "daily",
                    date = date,
                    value = score,
                    teacherUserId = me,
                    createdAt = now,
                    updatedAt = now,
                    deletedAt = null
                )
                db.evaluationDao().upsert(entity)
            }
            PendingWriteEntity.KIND_LESSON_LOG -> {
                val scheduleId = json.optString("schedule_id")
                val dateStr = json.optString("date")
                val status = json.optString("status")
                val covered = json.optString("covered", null)
                val homework = json.optString("homework", null)
                val notes = json.optString("notes", null)
                val date = DateParsers.parseLocalDate(dateStr) ?: LocalDate.now()

                val existingLog = db.lessonLogDao().getByScheduleAndDate(scheduleId, date)
                val entityId = existingLog?.id ?: "local:$scheduleId:$dateStr"

                val entity = LessonLogEntity(
                    id = entityId,
                    branchId = null,
                    scheduleId = scheduleId,
                    date = date,
                    status = status,
                    covered = covered,
                    homework = homework,
                    notes = notes,
                    teacherUserId = me,
                    createdAt = existingLog?.createdAt ?: now,
                    updatedAt = now,
                    deletedAt = null
                )
                db.lessonLogDao().upsert(entity)
            }
            PendingWriteEntity.KIND_NOTIFICATION_READ -> {
                val notifId = json.optString("id")
                val existing = db.notificationDao().getById(notifId)
                if (existing != null) {
                    db.notificationDao().upsert(existing.copy(readAt = now, updatedAt = now))
                }
            }
        }
    }

    suspend fun reapplyPending(items: List<PendingWriteEntity>) {
        val now = Instant.now()
        for (item in items) {
            applyOptimisticProjection(item.kind, item.naturalKey, item.payloadJson, now)
        }
    }

    fun scheduleFlush() {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<OutboxWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        } catch (_: Exception) {}
    }

    suspend fun flush(): Result<Unit> {
        return try {
            scheduleFlush()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        const val WORK_NAME = "outbox-flush"
    }
}
