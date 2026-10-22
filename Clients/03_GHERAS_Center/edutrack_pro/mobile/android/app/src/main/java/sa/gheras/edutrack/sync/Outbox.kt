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
import sa.gheras.edutrack.data.entity.AssignmentEntity
import sa.gheras.edutrack.data.entity.AssignmentStudentEntity
import sa.gheras.edutrack.data.entity.AttendanceExcuseEntity
import sa.gheras.edutrack.data.entity.EvaluationEntity
import sa.gheras.edutrack.data.entity.LessonLogEntity
import sa.gheras.edutrack.data.entity.PendingWriteEntity
import sa.gheras.edutrack.data.entity.PickupDelegateEntity
import sa.gheras.edutrack.data.entity.StudentAttendanceEntity
import sa.gheras.edutrack.data.entity.SubmissionEntity
import sa.gheras.edutrack.data.entity.SubmissionFileEntity
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

            var effectivePayloadJson = payloadJson
            if (kind == PendingWriteEntity.KIND_DAILY_EVAL) {
                val json = JSONObject(payloadJson)
                val studentId = json.optString("student_id")
                val dateStr = json.optString("date")
                val subject = json.optString("subject", "عام")
                val date = DateParsers.parseLocalDate(dateStr) ?: LocalDate.now()

                val existingPendingJson = existing?.payloadJson?.let {
                    try { JSONObject(it) } catch (_: Exception) { null }
                }
                if (existingPendingJson != null && existingPendingJson.has("prev_id")) {
                    json.put("prev_id", existingPendingJson.getString("prev_id"))
                    json.put("prev_value", existingPendingJson.getDouble("prev_value"))
                    if (existingPendingJson.has("prev_teacher_id")) json.put("prev_teacher_id", existingPendingJson.getString("prev_teacher_id"))
                    if (existingPendingJson.has("prev_created_at")) json.put("prev_created_at", existingPendingJson.getString("prev_created_at"))
                    if (existingPendingJson.has("prev_updated_at")) json.put("prev_updated_at", existingPendingJson.getString("prev_updated_at"))
                    if (existingPendingJson.has("prev_branch_id")) json.put("prev_branch_id", existingPendingJson.getString("prev_branch_id"))
                } else {
                    val existingEval = db.evaluationDao().getByStudentDateSubject(studentId, date, subject, "daily")
                    if (existingEval != null && !existingEval.id.startsWith("local:")) {
                        json.put("prev_id", existingEval.id)
                        json.put("prev_value", existingEval.value)
                        existingEval.teacherUserId?.let { json.put("prev_teacher_id", it) }
                        json.put("prev_created_at", existingEval.createdAt.toString())
                        json.put("prev_updated_at", existingEval.updatedAt.toString())
                        existingEval.branchId?.let { json.put("prev_branch_id", it) }
                    }
                }
                effectivePayloadJson = json.toString()
            }

            val writeEntity = PendingWriteEntity(
                id = id,
                kind = kind,
                naturalKey = naturalKey,
                payloadJson = effectivePayloadJson,
                createdAt = now,
                attempts = 0,
                lastError = null
            )
            db.pendingWriteDao().upsert(writeEntity)

            // Apply optimistic projection locally
            applyOptimisticProjection(kind, naturalKey, effectivePayloadJson, now)
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
                val note = json.optString("note").takeIf { it.isNotBlank() }
                val date = DateParsers.parseLocalDate(dateStr) ?: LocalDate.now()
                val isDraft = json.optBoolean("is_draft", false)
                val excuseId = json.optString("excuse_id").takeIf { it.isNotBlank() }

                val entity = StudentAttendanceEntity(
                    id = "local:$studentId:$dateStr",
                    branchId = null,
                    studentId = studentId,
                    date = date,
                    status = status,
                    note = note,
                    isDraft = isDraft,
                    excuseId = excuseId,
                    recordedByUserId = me,
                    createdAt = now,
                    updatedAt = now,
                    deletedAt = null
                )
                db.studentAttendanceDao().upsert(entity)
            }
            PendingWriteEntity.KIND_ATTENDANCE_RECORD -> {
                val itemsArr = json.optJSONArray("items")
                val dateStr = json.optString("date")
                val date = DateParsers.parseLocalDate(dateStr) ?: LocalDate.now()
                if (itemsArr != null) {
                    val entities = mutableListOf<StudentAttendanceEntity>()
                    for (i in 0 until itemsArr.length()) {
                        val itm = itemsArr.getJSONObject(i)
                        val studentId = itm.getString("student_id")
                        val status = itm.getString("status")
                        val note = itm.optString("note").takeIf { it.isNotBlank() }
                        entities.add(
                            StudentAttendanceEntity(
                                id = "local:$studentId:$dateStr",
                                branchId = null,
                                studentId = studentId,
                                date = date,
                                status = status,
                                note = note,
                                isDraft = true,
                                excuseId = null,
                                recordedByUserId = me,
                                createdAt = now,
                                updatedAt = now,
                                deletedAt = null
                            )
                        )
                    }
                    db.studentAttendanceDao().upsertAll(entities)
                } else {
                    val studentId = json.optString("student_id")
                    val status = json.optString("status")
                    val note = json.optString("note").takeIf { it.isNotBlank() }
                    val isDraft = json.optBoolean("is_draft", true)
                    val excuseId = json.optString("excuse_id").takeIf { it.isNotBlank() }

                    val entity = StudentAttendanceEntity(
                        id = "local:$studentId:$dateStr",
                        branchId = null,
                        studentId = studentId,
                        date = date,
                        status = status,
                        note = note,
                        isDraft = isDraft,
                        excuseId = excuseId,
                        recordedByUserId = me,
                        createdAt = now,
                        updatedAt = now,
                        deletedAt = null
                    )
                    db.studentAttendanceDao().upsert(entity)
                }
            }
            PendingWriteEntity.KIND_ATTENDANCE_APPROVE -> {
                // Room attendance approval marks local records for this date as non-draft
            }
            PendingWriteEntity.KIND_ATTENDANCE_EXCUSE -> {
                val excuseId = json.optString("id").takeIf { it.isNotBlank() } ?: "local:${UUID.randomUUID()}"
                val studentId = json.getString("student_id")
                val dateStr = json.getString("excuse_date")
                val date = DateParsers.parseLocalDate(dateStr) ?: LocalDate.now()
                val reasonText = json.getString("reason_text")
                val attachmentStorageKey = json.optString("attachment_storage_key").takeIf { it.isNotBlank() }
                val clientRequestId = json.optString("client_request_id").takeIf { it.isNotBlank() }

                val entity = AttendanceExcuseEntity(
                    id = excuseId,
                    branchId = null,
                    studentId = studentId,
                    excuseDate = date,
                    reasonText = reasonText,
                    attachmentStorageKey = attachmentStorageKey,
                    status = "pending",
                    supervisorNotes = null,
                    reviewedAt = null,
                    clientRequestId = clientRequestId,
                    createdAt = now,
                    updatedAt = now
                )
                db.attendanceExcuseDao().upsert(entity)
            }
            PendingWriteEntity.KIND_ATTENDANCE_EXCUSE_PATCH -> {
                val excuseId = json.getString("id")
                val existing = db.attendanceExcuseDao().getById(excuseId)
                if (existing != null) {
                    val newReason = json.optString("reason_text").takeIf { it.isNotBlank() } ?: existing.reasonText
                    val newAttachment = json.optString("attachment_storage_key").takeIf { it.isNotBlank() } ?: existing.attachmentStorageKey
                    db.attendanceExcuseDao().upsert(
                        existing.copy(
                            reasonText = newReason,
                            attachmentStorageKey = newAttachment,
                            status = "pending",
                            updatedAt = now
                        )
                    )
                }
            }
            PendingWriteEntity.KIND_DAILY_EVAL -> {
                val studentId = json.optString("student_id")
                val dateStr = json.optString("date")
                val subject = json.optString("subject", "عام")
                val score = json.optDouble("score", 0.0)
                val date = DateParsers.parseLocalDate(dateStr) ?: LocalDate.now()

                val prevBranchId = if (json.has("prev_branch_id")) json.optString("prev_branch_id").takeIf { it.isNotBlank() } else null
                val prevCreatedAt = if (json.has("prev_created_at")) {
                    try { Instant.parse(json.getString("prev_created_at")) } catch (_: Exception) { now }
                } else now

                val entity = EvaluationEntity(
                    id = "local:$studentId:$dateStr:$subject",
                    branchId = prevBranchId,
                    studentId = studentId,
                    subject = subject,
                    evalType = "daily",
                    date = date,
                    value = score,
                    teacherUserId = me,
                    createdAt = prevCreatedAt,
                    updatedAt = now,
                    deletedAt = null
                )
                db.evaluationDao().upsert(entity)
            }
            PendingWriteEntity.KIND_LESSON_LOG -> {
                val scheduleId = json.optString("schedule_id")
                val dateStr = json.optString("date")
                val status = json.optString("status")
                val covered = json.optString("covered").takeIf { it.isNotBlank() }
                val homework = json.optString("homework").takeIf { it.isNotBlank() }
                val notes = json.optString("notes").takeIf { it.isNotBlank() }
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
            PendingWriteEntity.KIND_NOTIFICATION_DELETE -> {
                val notifId = json.optString("id")
                if (notifId.isNotBlank()) {
                    db.notificationDao().softDelete(listOf(notifId), now)
                }
            }
            PendingWriteEntity.KIND_NOTIFICATION_CLEAR_READ -> {
                val idsArray = json.optJSONArray("ids")
                if (idsArray != null && idsArray.length() > 0) {
                    val ids = (0 until idsArray.length()).map { idsArray.getString(it) }
                    db.notificationDao().softDelete(ids, now)
                }
            }
            PendingWriteEntity.KIND_NOTIFICATION_BROADCAST -> {
                // Server fans out to target recipients.
            }
            PendingWriteEntity.KIND_ASSIGNMENT,
            PendingWriteEntity.KIND_ASSIGNMENT_CREATE -> {
                val assignmentId = json.optString("assignment_id")
                val title = json.optString("title")
                val subject = json.optString("subject").takeIf { it.isNotBlank() }
                val dueDateStr = json.optString("due_date").takeIf { it.isNotBlank() }
                val dueDate = dueDateStr?.let { DateParsers.parseLocalDate(it) }
                val instructions = json.optString("instructions").takeIf { it.isNotBlank() }
                val pageRef = json.optString("page_ref").takeIf { it.isNotBlank() }
                val teacherUserId = json.optString("teacher_user_id").takeIf { it.isNotBlank() } ?: me
                val branchId = json.optString("branch_id").takeIf { it.isNotBlank() }
                val isDraft = json.optBoolean("is_draft", false)

                val entity = AssignmentEntity(
                    id = assignmentId,
                    branchId = branchId,
                    title = title,
                    subject = subject,
                    kind = "homework",
                    dueDate = dueDate,
                    teacherUserId = teacherUserId,
                    instructions = instructions,
                    pageRef = pageRef,
                    isDraft = isDraft,
                    createdAt = now,
                    updatedAt = now,
                    deletedAt = null
                )
                db.assignmentDao().upsert(entity)

                val studentIdsArray = json.optJSONArray("student_ids")
                if (studentIdsArray != null && studentIdsArray.length() > 0) {
                    val links = (0 until studentIdsArray.length()).map { i ->
                        val studentId = studentIdsArray.getString(i)
                        AssignmentStudentEntity(
                            id = "local:$assignmentId:$studentId",
                            branchId = branchId,
                            assignmentId = assignmentId,
                            studentId = studentId,
                            createdAt = now,
                            updatedAt = now,
                            deletedAt = null
                        )
                    }
                    db.assignmentStudentDao().upsertAll(links)
                }
            }
            PendingWriteEntity.KIND_SUBMISSION_CREATE -> {
                val assignmentId = json.getString("assignment_id")
                val studentId = json.getString("student_id")
                val submissionId = json.optString("submission_id").ifBlank { "local:${UUID.randomUUID()}" }
                val attemptNumber = json.optInt("attempt_number", 1)
                val isLate = json.optBoolean("is_late", false)
                val note = json.optString("note").takeIf { it.isNotBlank() }
                val parentId = json.optString("parent_submission_id").takeIf { it.isNotBlank() }

                val entity = SubmissionEntity(
                    id = submissionId,
                    branchId = json.optString("branch_id").takeIf { it.isNotBlank() },
                    assignmentId = assignmentId,
                    studentId = studentId,
                    attemptNumber = attemptNumber,
                    parentSubmissionId = parentId,
                    gradeLevel = null,
                    revisionNote = null,
                    teacherFeedbackKey = null,
                    isLate = isLate,
                    submittedAt = now,
                    status = "submitted",
                    feedback = note,
                    createdAt = now,
                    updatedAt = now,
                    deletedAt = null
                )
                db.submissionDao().upsert(entity)

                val fileKeysArray = json.optJSONArray("files")
                if (fileKeysArray != null && fileKeysArray.length() > 0) {
                    val files = (0 until fileKeysArray.length()).map { i ->
                        val key = fileKeysArray.getString(i)
                        SubmissionFileEntity(
                            id = "local:$submissionId:$i",
                            branchId = null,
                            submissionId = submissionId,
                            storageKey = key,
                            createdAt = now,
                            updatedAt = now
                        )
                    }
                    db.submissionFileDao().upsertAll(files)
                }
            }
            PendingWriteEntity.KIND_CREATE_PICKUP_DELEGATE -> {
                val studentId = json.getString("student_id")
                val name = json.getString("name")
                val phone = json.getString("phone")
                val relation = json.getString("relation")
                val nationalId = json.optString("national_id")
                val delegateId = json.optString("id").ifBlank { "local:${UUID.randomUUID()}" }
                val maskedId = if (nationalId.length > 4) {
                    nationalId.take(2) + "*".repeat(nationalId.length - 4) + nationalId.takeLast(2)
                } else nationalId

                val entity = PickupDelegateEntity(
                    id = delegateId,
                    studentId = studentId,
                    name = name,
                    phone = phone,
                    relation = relation,
                    isApproved = true,
                    clientRequestId = json.optString("client_request_id").takeIf { it.isNotBlank() },
                    nationalIdMasked = maskedId.takeIf { it.isNotBlank() },
                    createdAt = now,
                    updatedAt = now,
                    deletedAt = null
                )
                db.pickupDao().upsert(entity)
            }
            PendingWriteEntity.KIND_DELETE_PICKUP_DELEGATE -> {
                val delegateId = json.getString("delegate_id")
                val existing = db.pickupDao().getDelegateById(delegateId)
                if (existing != null) {
                    db.pickupDao().upsert(existing.copy(deletedAt = now, updatedAt = now))
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
