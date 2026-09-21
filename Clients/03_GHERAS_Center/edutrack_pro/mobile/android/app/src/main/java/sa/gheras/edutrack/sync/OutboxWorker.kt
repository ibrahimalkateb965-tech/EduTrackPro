package sa.gheras.edutrack.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.json.JSONObject
import retrofit2.HttpException
import sa.gheras.edutrack.GherasApp
import sa.gheras.edutrack.data.entity.PendingWriteEntity
import sa.gheras.edutrack.data.remote.ErrorMapper
import sa.gheras.edutrack.data.remote.dto.AttendanceItemBody
import sa.gheras.edutrack.data.remote.dto.DailyEvalItemBody
import sa.gheras.edutrack.data.remote.dto.LessonLogBody
import java.io.IOException

class OutboxWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? GherasApp ?: return Result.failure()
        val container = app.container
        val db = container.database
        val meApi = container.meApi
        val sessionRepo = container.sessionRepository

        val pendingList = db.pendingWriteDao().listPending()
        if (pendingList.isEmpty()) return Result.success()

        for (item in pendingList) {
            try {
                flushItem(item, meApi)
                // 2xx success: remove row from pending_writes
                db.pendingWriteDao().deleteById(item.id)
            } catch (e: HttpException) {
                val code = e.code()
                if (code == 401) {
                    sessionRepo.onUnauthorized()
                    return Result.retry()
                } else if (code == 403 || code == 404 || code == 422) {
                    // Terminal failure: mark attempts = -1 and revert optimistic projection
                    val error = ErrorMapper.map(e)
                    db.pendingWriteDao().markFailed(item.id, error.userMessage)
                    revertOptimisticProjection(item, db)
                } else {
                    // 5xx server error
                    db.pendingWriteDao().recordAttempt(item.id, e.message())
                    return Result.retry()
                }
            } catch (e: IOException) {
                // Network failure: retry later
                db.pendingWriteDao().recordAttempt(item.id, e.message)
                return Result.retry()
            } catch (e: Exception) {
                db.pendingWriteDao().markFailed(item.id, e.message ?: "unknown error")
            }
        }

        return Result.success()
    }

    private suspend fun flushItem(item: PendingWriteEntity, meApi: sa.gheras.edutrack.data.remote.MeApi) {
        val json = JSONObject(item.payloadJson)

        when (item.kind) {
            PendingWriteEntity.KIND_ATTENDANCE -> {
                val body = AttendanceItemBody(
                    studentId = json.getString("student_id"),
                    date = json.getString("date"),
                    status = json.getString("status"),
                    note = json.optString("note", null)
                )
                val response = meApi.postAttendanceBatch(listOf(body))
                if (!response.isSuccessful) throw HttpException(response)
            }
            PendingWriteEntity.KIND_DAILY_EVAL -> {
                val body = DailyEvalItemBody(
                    studentId = json.getString("student_id"),
                    date = json.getString("date"),
                    subject = json.optString("subject", "عام"),
                    value = json.optString("value", null),
                    score = json.optDouble("score", 0.0),
                    notes = json.optString("notes", null)
                )
                val response = meApi.postDailyEvaluationsBatch(listOf(body))
                if (!response.isSuccessful) throw HttpException(response)
            }
            PendingWriteEntity.KIND_LESSON_LOG -> {
                val body = LessonLogBody(
                    scheduleId = json.getString("schedule_id"),
                    date = json.getString("date"),
                    status = json.getString("status"),
                    covered = json.optString("covered", null),
                    homework = json.optString("homework", null),
                    notes = json.optString("notes", null)
                )
                meApi.postLessonLog(body)
            }
            PendingWriteEntity.KIND_NOTIFICATION_READ -> {
                val notifId = json.getString("id")
                meApi.markNotificationRead(notifId)
            }
        }
    }

    private suspend fun revertOptimisticProjection(
        item: PendingWriteEntity,
        db: sa.gheras.edutrack.data.db.GherasDatabase
    ) {
        val json = JSONObject(item.payloadJson)
        when (item.kind) {
            PendingWriteEntity.KIND_ATTENDANCE -> {
                val studentId = json.optString("student_id")
                val dateStr = json.optString("date")
                db.studentAttendanceDao().deleteById("local:$studentId:$dateStr")
            }
            PendingWriteEntity.KIND_DAILY_EVAL -> {
                val studentId = json.optString("student_id")
                val dateStr = json.optString("date")
                val subject = json.optString("subject", "عام")
                db.evaluationDao().deleteById("local:$studentId:$dateStr:$subject")
            }
            PendingWriteEntity.KIND_LESSON_LOG -> {
                val scheduleId = json.optString("schedule_id")
                val dateStr = json.optString("date")
                db.lessonLogDao().deleteById("local:$scheduleId:$dateStr")
            }
        }
    }
}
