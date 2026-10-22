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
import sa.gheras.edutrack.data.remote.dto.SaveDraftAttendanceRequest
import sa.gheras.edutrack.data.remote.dto.CreateAssignmentBody
import sa.gheras.edutrack.data.remote.dto.CreateDelegateRequest
import sa.gheras.edutrack.data.remote.dto.DailyEvalItemBody
import sa.gheras.edutrack.data.remote.dto.LessonLogBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import sa.gheras.edutrack.data.entity.EvaluationEntity
import sa.gheras.edutrack.data.entity.PickupDelegateEntity
import sa.gheras.edutrack.di.AppContainer
import sa.gheras.edutrack.data.repo.mappers.AssignmentMappers
import sa.gheras.edutrack.data.repo.mappers.DateParsers
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CancellationException

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

        var hadWork = false
        for (item in pendingList) {
            try {
                flushItem(item, meApi, db, container)
                // 2xx success: remove row from pending_writes
                db.pendingWriteDao().deleteById(item.id)
                hadWork = true
            } catch (e: HttpException) {
                val code = e.code()
                if (code == 401) {
                    sessionRepo.onUnauthorized()
                    return Result.retry()
                } else if (code == 403 || code == 404 || code == 409 || code == 422) {
                    // Terminal failure: mark attempts = -1 and revert optimistic projection
                    val error = ErrorMapper.map(e)
                    db.pendingWriteDao().markFailed(item.id, error.userMessage)
                    revertOptimisticProjection(item, db)
                    hadWork = true
                } else {
                    // 5xx server error
                    db.pendingWriteDao().recordAttempt(item.id, e.message())
                    return Result.retry()
                }
            } catch (e: IOException) {
                // Network failure: retry later
                db.pendingWriteDao().recordAttempt(item.id, e.message)
                return Result.retry()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                db.pendingWriteDao().markFailed(item.id, e.message ?: "unknown error")
                revertOptimisticProjection(item, db)
                hadWork = true
            }
        }

        if (hadWork) {
            try {
                container.pullSync.requestFull()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }

        return Result.success()
    }

    companion object {
        internal suspend fun flushItem(
            item: PendingWriteEntity,
            meApi: sa.gheras.edutrack.data.remote.MeApi,
            db: sa.gheras.edutrack.data.db.GherasDatabase,
            container: AppContainer? = null
        ) {
        val json = JSONObject(item.payloadJson)

        when (item.kind) {
            PendingWriteEntity.KIND_ATTENDANCE -> {
                val rawStatus = json.getString("status")
                val safeStatus = if (rawStatus == "غياب بعذر") "غائب" else rawStatus
                val body = AttendanceItemBody(
                    studentId = json.getString("student_id"),
                    date = json.getString("date"),
                    status = safeStatus,
                    note = json.optString("note").takeIf { it.isNotBlank() }
                )
                val response = meApi.postAttendanceBatch(listOf(body))
                if (!response.isSuccessful) throw HttpException(response)
            }
            PendingWriteEntity.KIND_ATTENDANCE_RECORD -> {
                val roomId = json.optString("room_id")
                val dateStr = json.getString("date")
                val itemsArr = json.optJSONArray("items")
                if (itemsArr != null && roomId.isNotBlank()) {
                    val itemsList = mutableListOf<AttendanceItemBody>()
                    for (i in 0 until itemsArr.length()) {
                        val itm = itemsArr.getJSONObject(i)
                        val rawStatus = itm.getString("status")
                        val safeStatus = if (rawStatus == "غياب بعذر") "غائب" else rawStatus
                        itemsList.add(
                            AttendanceItemBody(
                                studentId = itm.getString("student_id"),
                                date = dateStr,
                                status = safeStatus,
                                note = itm.optString("note").takeIf { it.isNotBlank() }
                            )
                        )
                    }
                    val req = SaveDraftAttendanceRequest(
                        roomId = roomId,
                        date = dateStr,
                        items = itemsList
                    )
                    val response = meApi.saveAttendanceDraft(req)
                    if (!response.isSuccessful) throw HttpException(response)
                } else {
                    val rawStatus = json.getString("status")
                    val safeStatus = if (rawStatus == "غياب بعذر") "غائب" else rawStatus
                    val body = AttendanceItemBody(
                        studentId = json.getString("student_id"),
                        date = json.getString("date"),
                        status = safeStatus,
                        note = json.optString("note").takeIf { it.isNotBlank() }
                    )
                    val req = SaveDraftAttendanceRequest(
                        roomId = roomId.ifBlank { "00000000-0000-0000-0000-000000000000" },
                        date = json.getString("date"),
                        items = listOf(body)
                    )
                    val response = meApi.saveAttendanceDraft(req)
                    if (!response.isSuccessful) throw HttpException(response)
                }
            }
            PendingWriteEntity.KIND_ATTENDANCE_APPROVE -> {
                val roomId = json.getString("room_id")
                val date = json.getString("date")
                meApi.approveRoomAttendance(roomId, date)
            }
            PendingWriteEntity.KIND_ATTENDANCE_EXCUSE -> {
                var storageKey = json.optString("attachment_storage_key").takeIf { it.isNotBlank() }
                val localPath = json.optString("local_file_path").takeIf { it.isNotBlank() }
                if (storageKey == null && localPath != null) {
                    val file = File(localPath)
                    if (file.exists() && file.isFile) {
                        if (file.length() > 25 * 1024 * 1024) {
                            throw IllegalStateException("حجم الملف يتجاوز الحد الأقصى 25 ميجابايت")
                        }
                        val mediaType = when (file.extension.lowercase()) {
                            "pdf" -> "application/pdf".toMediaTypeOrNull()
                            "jpg", "jpeg" -> "image/jpeg".toMediaTypeOrNull()
                            "png" -> "image/png".toMediaTypeOrNull()
                            else -> "application/octet-stream".toMediaTypeOrNull()
                        }
                        val uploadRes = meApi.uploadMedia(file.name, file.asRequestBody(mediaType))
                        val fetchedKey = uploadRes.storageKey ?: throw IllegalStateException("Server did not return storage_key for uploaded attachment")
                        storageKey = fetchedKey
                        json.put("attachment_storage_key", fetchedKey)
                        db.pendingWriteDao().upsert(item.copy(payloadJson = json.toString()))
                    }
                }
                val req = sa.gheras.edutrack.data.remote.dto.CreateExcuseRequest(
                    studentId = json.getString("student_id"),
                    excuseDate = json.getString("excuse_date"),
                    reasonText = json.getString("reason_text"),
                    attachmentStorageKey = storageKey,
                    clientRequestId = json.optString("client_request_id").takeIf { it.isNotBlank() }
                )
                val excuseDto = meApi.createExcuse(req)
                val localId = json.optString("id")
                if (localId.isNotBlank() && localId.startsWith("local:")) {
                    val existingLocal = db.attendanceExcuseDao().getById(localId)
                    if (existingLocal != null) {
                        db.attendanceExcuseDao().deleteById(localId)
                        db.attendanceExcuseDao().upsert(
                            existingLocal.copy(
                                id = excuseDto.id,
                                status = excuseDto.status,
                                attachmentStorageKey = excuseDto.attachmentStorageKey
                            )
                        )
                    }
                }
            }
            PendingWriteEntity.KIND_ATTENDANCE_EXCUSE_PATCH -> {
                val excuseId = json.getString("id")
                var storageKey = json.optString("attachment_storage_key").takeIf { it.isNotBlank() }
                val localPath = json.optString("local_file_path").takeIf { it.isNotBlank() }
                if (storageKey == null && localPath != null) {
                    val file = File(localPath)
                    if (file.exists() && file.isFile) {
                        if (file.length() > 25 * 1024 * 1024) {
                            throw IllegalStateException("حجم الملف يتجاوز الحد الأقصى 25 ميجابايت")
                        }
                        val mediaType = when (file.extension.lowercase()) {
                            "pdf" -> "application/pdf".toMediaTypeOrNull()
                            "jpg", "jpeg" -> "image/jpeg".toMediaTypeOrNull()
                            "png" -> "image/png".toMediaTypeOrNull()
                            else -> "application/octet-stream".toMediaTypeOrNull()
                        }
                        val uploadRes = meApi.uploadMedia(file.name, file.asRequestBody(mediaType))
                        val fetchedKey = uploadRes.storageKey ?: throw IllegalStateException("Server did not return storage_key for uploaded attachment")
                        storageKey = fetchedKey
                        json.put("attachment_storage_key", fetchedKey)
                        db.pendingWriteDao().upsert(item.copy(payloadJson = json.toString()))
                    }
                }
                val req = sa.gheras.edutrack.data.remote.dto.PatchExcuseRequest(
                    reasonText = json.optString("reason_text").takeIf { it.isNotBlank() },
                    attachmentStorageKey = storageKey
                )
                val patched = meApi.patchExcuse(excuseId, req)
                val existing = db.attendanceExcuseDao().getById(excuseId)
                if (existing != null) {
                    db.attendanceExcuseDao().upsert(
                        existing.copy(
                            reasonText = patched.reasonText,
                            attachmentStorageKey = patched.attachmentStorageKey,
                            status = patched.status
                        )
                    )
                }
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
            PendingWriteEntity.KIND_ASSIGNMENT,
            PendingWriteEntity.KIND_ASSIGNMENT_CREATE -> {
                val studentIdsList = mutableListOf<String>()
                val arr = json.optJSONArray("student_ids")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        studentIdsList.add(arr.getString(i))
                    }
                }

                val filesList = mutableListOf<sa.gheras.edutrack.data.remote.dto.AssignmentFileDto>()
                val fArr = json.optJSONArray("files")
                if (fArr != null) {
                    for (i in 0 until fArr.length()) {
                        val fObj = fArr.getJSONObject(i)
                        filesList.add(
                            sa.gheras.edutrack.data.remote.dto.AssignmentFileDto(
                                storageKey = fObj.optString("storage_key").takeIf { it.isNotBlank() },
                                mediaType = fObj.optString("media_type", "image"),
                                fileName = fObj.optString("file_name").takeIf { it.isNotBlank() },
                                bytes = fObj.optLong("bytes", 0L)
                            )
                        )
                    }
                }

                val assignmentId = json.optString("assignment_id")
                var instructions = json.optString("instructions").takeIf { it.isNotBlank() }
                if (!instructions.isNullOrBlank()) {
                    val (cleanText, attachments) = sa.gheras.edutrack.ui.teacher.media.AssignmentMediaParser.parse(instructions)
                    val currentAttachments = attachments.toMutableList()

                    for (i in currentAttachments.indices) {
                        val att = currentAttachments[i]
                        if (att.uri.contains("/me/files/")) {
                            // Already uploaded server file from a previous attempt
                            val storageKey = att.uri.substringAfter("/me/files/")
                            if (filesList.none { it.storageKey == storageKey }) {
                                val mediaType = when (att.kind) {
                                    "pdf_document" -> "document"
                                    "voice_note" -> "audio"
                                    "video_link" -> "video"
                                    else -> "image"
                                }
                                filesList.add(
                                    sa.gheras.edutrack.data.remote.dto.AssignmentFileDto(
                                        storageKey = storageKey,
                                        mediaType = mediaType,
                                        fileName = storageKey.substringAfterLast("/"),
                                        bytes = 0L
                                    )
                                )
                            }
                        } else if (!att.uri.startsWith("http://") && !att.uri.startsWith("https://") && !att.uri.startsWith("/api/")) {
                            // Local file on device to upload
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
                                val res = meApi.uploadMedia(file.name, reqBody)
                                val storageKey = res.storageKey ?: res.url.substringAfterLast("/")
                                val fileDto = sa.gheras.edutrack.data.remote.dto.AssignmentFileDto(
                                    storageKey = storageKey,
                                    mediaType = when (file.extension.lowercase()) {
                                        "pdf" -> "document"
                                        "mp3", "m4a", "aac" -> "audio"
                                        "mp4" -> "video"
                                        else -> "image"
                                    },
                                    fileName = file.name,
                                    bytes = file.length()
                                )
                                filesList.add(fileDto)
                                currentAttachments[i] = att.copy(uri = res.url)

                                // J1 & J1b: Immediately persist after EACH successful upload to Room and Outbox
                                instructions = sa.gheras.edutrack.ui.teacher.media.AssignmentMediaParser.serialize(cleanText, currentAttachments)
                                db.assignmentDao().updateInstructions(assignmentId, instructions, Instant.now())

                                json.put("instructions", instructions)
                                val filesArr = org.json.JSONArray()
                                for (f in filesList) {
                                    filesArr.put(org.json.JSONObject().apply {
                                        put("storage_key", f.storageKey)
                                        put("media_type", f.mediaType)
                                        put("file_name", f.fileName)
                                        put("bytes", f.bytes)
                                    })
                                }
                                json.put("files", filesArr)
                                db.pendingWriteDao().upsert(item.copy(payloadJson = json.toString()))
                            }
                        }
                        // J2b: External links (youtube.com, forms.gle) are kept in instructions only and never added to filesList
                    }

                    if (filesList.isNotEmpty() && (json.optJSONArray("files") == null || json.optJSONArray("files")!!.length() != filesList.size)) {
                        val filesArr = org.json.JSONArray()
                        for (f in filesList) {
                            filesArr.put(org.json.JSONObject().apply {
                                put("storage_key", f.storageKey)
                                put("media_type", f.mediaType)
                                put("file_name", f.fileName)
                                put("bytes", f.bytes)
                            })
                        }
                        json.put("files", filesArr)
                        db.pendingWriteDao().upsert(item.copy(payloadJson = json.toString()))
                    }
                }

                val dueDateStr = json.optString("due_date").takeIf { it.isNotBlank() && it != "null" }
                val isDraftVal = json.optBoolean("is_draft", false)
                val roomIdVal = json.optString("room_id").takeIf { it.isNotBlank() }

                val body = sa.gheras.edutrack.data.remote.dto.CreateAssignmentRequest(
                    id = json.optString("assignment_id").takeIf { it.isNotBlank() },
                    title = json.getString("title"),
                    subject = json.optString("subject").takeIf { it.isNotBlank() },
                    roomId = roomIdVal,
                    dueDate = dueDateStr,
                    instructions = instructions,
                    pageRef = json.optString("page_ref").takeIf { it.isNotBlank() },
                    studentIds = studentIdsList,
                    isDraft = isDraftVal,
                    files = filesList
                )
                val res = meApi.createAssignmentModern(body)
                val existing = db.assignmentDao().getById(res.id)
                if (existing != null) {
                    db.assignmentDao().update(AssignmentMappers.assignmentToEntity(res))
                } else {
                    db.assignmentDao().upsert(AssignmentMappers.assignmentToEntity(res))
                }
                val serverLinks = AssignmentMappers.assignmentStudentsToEntities(res)
                if (serverLinks.isNotEmpty()) {
                    db.assignmentStudentDao().deleteByAssignment(res.id)
                    db.assignmentStudentDao().upsertAll(serverLinks)
                }
            }
            PendingWriteEntity.KIND_CREATE_PICKUP_DELEGATE -> {
                val body = CreateDelegateRequest(
                    studentId = json.getString("student_id"),
                    name = json.getString("name"),
                    nationalId = json.getString("national_id"),
                    phone = json.getString("phone"),
                    relation = json.getString("relation"),
                    clientRequestId = json.optString("client_request_id").takeIf { it.isNotBlank() }
                )
                val api = container?.pickupApi ?: throw IllegalStateException("AppContainer required for pickup delegate")
                api.createDelegate(body)
            }
            PendingWriteEntity.KIND_DELETE_PICKUP_DELEGATE -> {
                val delegateId = json.getString("delegate_id")
                val api = container?.pickupApi ?: throw IllegalStateException("AppContainer required for pickup delegate")
                api.deleteDelegate(delegateId)
            }
            PendingWriteEntity.KIND_SUBMISSION_CREATE -> {
                val assignmentId = json.getString("assignment_id")
                val studentId = json.getString("student_id")
                val note = json.optString("note").takeIf { it.isNotBlank() }
                val clientRequestId = json.optString("client_request_id").takeIf { it.isNotBlank() }
                val filesArray = json.optJSONArray("files")
                val filesList = mutableListOf<String>()
                if (filesArray != null) {
                    for (i in 0 until filesArray.length()) {
                        filesList.add(filesArray.getString(i))
                    }
                }

                val finalStorageKeys = filesList.map { keyOrPath ->
                    val localFile = File(keyOrPath)
                    if (localFile.exists() && localFile.isFile) {
                        val mediaType = when (localFile.extension.lowercase()) {
                            "pdf" -> "application/pdf".toMediaTypeOrNull()
                            "jpg", "jpeg" -> "image/jpeg".toMediaTypeOrNull()
                            "png" -> "image/png".toMediaTypeOrNull()
                            "mp4" -> "video/mp4".toMediaTypeOrNull()
                            "m4a", "aac" -> "audio/mp4".toMediaTypeOrNull()
                            "mp3" -> "audio/mpeg".toMediaTypeOrNull()
                            else -> "application/octet-stream".toMediaTypeOrNull()
                        }
                        val uploadRes = meApi.uploadMedia(localFile.name, localFile.asRequestBody(mediaType))
                        uploadRes.storageKey ?: uploadRes.url
                    } else {
                        keyOrPath
                    }
                }

                val req = sa.gheras.edutrack.data.remote.dto.SubmitAssignmentRequest(
                    studentId = studentId,
                    note = note,
                    files = finalStorageKeys,
                    clientRequestId = clientRequestId
                )
                val res = meApi.submitAssignment(assignmentId, req)

                val entity = AssignmentMappers.submissionToEntity(res)
                db.submissionDao().upsert(entity)
                if (res.files.isNotEmpty()) {
                    val fileEntities = AssignmentMappers.submissionFilesToEntities(res)
                    db.submissionFileDao().replaceScope(entity.id, fileEntities)
                }
            }
        }
    }

    internal suspend fun revertOptimisticProjection(
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
            PendingWriteEntity.KIND_ATTENDANCE_RECORD -> {
                val itemsArr = json.optJSONArray("items")
                val dateStr = json.optString("date")
                if (itemsArr != null) {
                    for (i in 0 until itemsArr.length()) {
                        val studentId = itemsArr.getJSONObject(i).optString("student_id")
                        db.studentAttendanceDao().deleteById("local:$studentId:$dateStr")
                    }
                } else {
                    val studentId = json.optString("student_id")
                    db.studentAttendanceDao().deleteById("local:$studentId:$dateStr")
                }
            }
            PendingWriteEntity.KIND_ATTENDANCE_EXCUSE -> {
                val excuseId = json.optString("id")
                if (excuseId.isNotBlank()) {
                    db.attendanceExcuseDao().deleteById(excuseId)
                }
            }
            PendingWriteEntity.KIND_ATTENDANCE_EXCUSE_PATCH -> {
                // On patch failure, do not delete the excuse; it remains in its previous state
            }
            PendingWriteEntity.KIND_DAILY_EVAL -> {
                val studentId = json.optString("student_id")
                val dateStr = json.optString("date")
                val subject = json.optString("subject", "عام")
                val date = DateParsers.parseLocalDate(dateStr) ?: LocalDate.now()

                db.evaluationDao().deleteById("local:$studentId:$dateStr:$subject")

                if (json.has("prev_id")) {
                    try {
                        val prevEntity = EvaluationEntity(
                            id = json.getString("prev_id"),
                            branchId = if (json.has("prev_branch_id")) json.optString("prev_branch_id").takeIf { it.isNotBlank() } else null,
                            studentId = studentId,
                            subject = subject,
                            evalType = "daily",
                            date = date,
                            value = json.getDouble("prev_value"),
                            teacherUserId = if (json.has("prev_teacher_id")) json.optString("prev_teacher_id").takeIf { it.isNotBlank() } else null,
                            createdAt = Instant.parse(json.getString("prev_created_at")),
                            updatedAt = Instant.parse(json.getString("prev_updated_at")),
                            deletedAt = null
                        )
                        db.evaluationDao().upsert(prevEntity)
                    } catch (_: Exception) {}
                }
            }
            PendingWriteEntity.KIND_LESSON_LOG -> {
                val scheduleId = json.optString("schedule_id")
                val dateStr = json.optString("date")
                db.lessonLogDao().deleteById("local:$scheduleId:$dateStr")
            }
            PendingWriteEntity.KIND_ASSIGNMENT,
            PendingWriteEntity.KIND_ASSIGNMENT_CREATE -> {
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
            PendingWriteEntity.KIND_CREATE_PICKUP_DELEGATE -> {
                val delegateId = json.optString("id")
                val existing = if (delegateId.isNotBlank()) db.pickupDao().getDelegateById(delegateId) else null
                if (existing != null) {
                    db.pickupDao().upsert(existing.copy(deletedAt = Instant.now()))
                }
            }
            PendingWriteEntity.KIND_DELETE_PICKUP_DELEGATE -> {
                val delegateId = json.getString("delegate_id")
                val existing = db.pickupDao().getDelegateByIdIncludingDeleted(delegateId)
                if (existing != null) {
                    db.pickupDao().upsert(existing.copy(deletedAt = null))
                }
            }
            PendingWriteEntity.KIND_SUBMISSION_CREATE -> {
                val submissionId = json.optString("submission_id")
                if (submissionId.isNotBlank()) {
                    db.submissionFileDao().deleteBySubmission(submissionId)
                    db.submissionDao().deleteById(submissionId)
                }
            }
        }
    }
}
}
