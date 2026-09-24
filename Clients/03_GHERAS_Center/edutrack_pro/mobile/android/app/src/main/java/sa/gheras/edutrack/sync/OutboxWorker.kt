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
import sa.gheras.edutrack.data.remote.dto.CreateAssignmentBody
import sa.gheras.edutrack.data.remote.dto.DailyEvalItemBody
import sa.gheras.edutrack.data.remote.dto.LessonLogBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
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
                flushItem(item, meApi, db)
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
                revertOptimisticProjection(item, db)
            }
        }

        return Result.success()
    }

    private suspend fun flushItem(
        item: PendingWriteEntity,
        meApi: sa.gheras.edutrack.data.remote.MeApi,
        db: sa.gheras.edutrack.data.db.GherasDatabase
    ) {
        val json = JSONObject(item.payloadJson)

        when (item.kind) {
            PendingWriteEntity.KIND_ATTENDANCE -> {
                val body = AttendanceItemBody(
                    studentId = json.getString("student_id"),
                    date = json.getString("date"),
                    status = json.getString("status"),
                    note = json.optString("note").takeIf { it.isNotBlank() }
                )
                val response = meApi.postAttendanceBatch(listOf(body))
                if (!response.isSuccessful) throw HttpException(response)
            }
            PendingWriteEntity.KIND_DAILY_EVAL -> {
                val body = DailyEvalItemBody(
                    studentId = json.getString("student_id"),
                    date = json.getString("date"),
                    subject = json.optString("subject", "عام"),
                    value = json.optString("value").takeIf { it.isNotBlank() },
                    score = json.optDouble("score", 0.0),
                    notes = json.optString("notes").takeIf { it.isNotBlank() }
                )
                val response = meApi.postDailyEvaluationsBatch(listOf(body))
                if (!response.isSuccessful) throw HttpException(response)
            }
            PendingWriteEntity.KIND_LESSON_LOG -> {
                val body = LessonLogBody(
                    scheduleId = json.getString("schedule_id"),
                    date = json.getString("date"),
                    status = json.getString("status"),
                    covered = json.optString("covered").takeIf { it.isNotBlank() },
                    homework = json.optString("homework").takeIf { it.isNotBlank() },
                    notes = json.optString("notes").takeIf { it.isNotBlank() }
                )
                meApi.postLessonLog(body)
            }
            PendingWriteEntity.KIND_NOTIFICATION_READ -> {
                val notifId = json.getString("id")
                meApi.markNotificationRead(notifId)
            }
            PendingWriteEntity.KIND_NOTIFICATION_DELETE -> {
                val notifId = json.getString("id")
                try {
                    val resp = meApi.deleteNotification(notifId)
                    if (!resp.isSuccessful && resp.code() != 404) {
                        throw HttpException(resp)
                    }
                } catch (e: HttpException) {
                    if (e.code() != 404) throw e
                }
            }
            PendingWriteEntity.KIND_NOTIFICATION_CLEAR_READ -> {
                val idsList = mutableListOf<String>()
                val arr = json.optJSONArray("ids")
                if (arr != null) {
                    for (i in 0 until arr.length()) idsList.add(arr.getString(i))
                }
                if (idsList.isNotEmpty()) {
                    val resp = meApi.clearReadNotifications(sa.gheras.edutrack.data.remote.dto.ClearReadBody(idsList))
                    if (!resp.isSuccessful && resp.code() != 404) {
                        throw HttpException(resp)
                    }
                }
            }
            PendingWriteEntity.KIND_NOTIFICATION_BROADCAST -> {
                val studentIdsList = mutableListOf<String>()
                val arr = json.optJSONArray("student_ids")
                if (arr != null) {
                    for (i in 0 until arr.length()) studentIdsList.add(arr.getString(i))
                }
                val body = sa.gheras.edutrack.data.remote.dto.BroadcastBody(
                    id = json.getString("id"),
                    title = json.getString("title"),
                    body = json.optString("body").takeIf { it.isNotBlank() },
                    priority = json.optString("priority", "normal"),
                    roomId = json.optString("room_id").takeIf { it.isNotBlank() },
                    studentIds = studentIdsList,
                    includeGuardians = json.optBoolean("include_guardians", true),
                    includeStudents = json.optBoolean("include_students", true)
                )
                meApi.postBroadcast(body)
            }
            PendingWriteEntity.KIND_ASSIGNMENT -> {
                val studentIdsList = mutableListOf<String>()
                val arr = json.optJSONArray("student_ids")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        studentIdsList.add(arr.getString(i))
                    }
                }

                var instructions = json.optString("instructions").takeIf { it.isNotBlank() }
                if (!instructions.isNullOrBlank()) {
                    val (cleanText, attachments) = sa.gheras.edutrack.ui.teacher.media.AssignmentMediaParser.parse(instructions)
                    var hasUploaded = false
                    val updatedAttachments = attachments.map { att ->
                        if (!att.uri.startsWith("http://") && !att.uri.startsWith("https://")) {
                            val file = File(att.uri)
                            if (file.exists() && file.isFile) {
                                if (file.length() > 25 * 1024 * 1024) {
                                    throw IllegalStateException("حجم الملف يتجاوز الحد الأقصى 25 ميجابايت")
                                }
                                val mediaType = when (file.extension.lowercase()) {
                                    "pdf" -> "application/pdf".toMediaTypeOrNull()
                                    "jpg", "jpeg" -> "image/jpeg".toMediaTypeOrNull()
                                    "png" -> "image/png".toMediaTypeOrNull()
                                    "m4a", "aac" -> "audio/mp4".toMediaTypeOrNull()
                                    "mp3" -> "audio/mpeg".toMediaTypeOrNull()
                                    else -> "application/octet-stream".toMediaTypeOrNull()
                                }
                                val reqBody = file.asRequestBody(mediaType)
                                val res = meApi.uploadAttachment(file.name, reqBody)
                                hasUploaded = true
                                att.copy(uri = res.url)
                            } else {
                                att
                            }
                        } else {
                            att
                        }
                    }
                    if (hasUploaded) {
                        instructions = sa.gheras.edutrack.ui.teacher.media.AssignmentMediaParser.serialize(cleanText, updatedAttachments)
                        val assignmentId = json.optString("assignment_id")
                        val existing = db.assignmentDao().getById(assignmentId)
                        if (existing != null) {
                            db.assignmentDao().upsert(existing.copy(instructions = instructions))
                        }
                        // Update outbox pending item payload to prevent re-uploading on retry
                        json.put("instructions", instructions)
                        db.pendingWriteDao().upsert(item.copy(payloadJson = json.toString()))
                    }
                }

                val body = CreateAssignmentBody(
                    id = json.optString("assignment_id").takeIf { it.isNotBlank() },
                    title = json.getString("title"),
                    subject = json.optString("subject").takeIf { it.isNotBlank() },
                    dueDate = json.getString("due_date"),
                    instructions = instructions,
                    pageRef = json.optString("page_ref").takeIf { it.isNotBlank() },
                    studentIds = studentIdsList
                )
                meApi.postAssignment(body)
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
            PendingWriteEntity.KIND_ASSIGNMENT -> {
                val assignmentId = json.optString("assignment_id")
                db.assignmentStudentDao().deleteByAssignment(assignmentId)
                db.assignmentDao().deleteById(assignmentId)
            }
            PendingWriteEntity.KIND_NOTIFICATION_DELETE -> {
                val notifId = json.optString("id")
                if (notifId.isNotBlank()) {
                    db.notificationDao().restore(listOf(notifId))
                }
            }
            PendingWriteEntity.KIND_NOTIFICATION_CLEAR_READ -> {
                val idsList = mutableListOf<String>()
                val arr = json.optJSONArray("ids")
                if (arr != null) {
                    for (i in 0 until arr.length()) idsList.add(arr.getString(i))
                }
                if (idsList.isNotEmpty()) {
                    db.notificationDao().restore(idsList)
                }
            }
        }
    }
}
