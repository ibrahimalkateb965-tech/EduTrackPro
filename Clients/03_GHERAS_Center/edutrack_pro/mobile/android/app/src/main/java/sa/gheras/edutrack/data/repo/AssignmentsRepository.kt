package sa.gheras.edutrack.data.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import sa.gheras.edutrack.data.dao.AssignmentDao
import sa.gheras.edutrack.data.dao.AssignmentStudentDao
import sa.gheras.edutrack.data.dao.SubmissionDao
import sa.gheras.edutrack.data.dao.SubmissionFileDao
import sa.gheras.edutrack.data.entity.AssignmentEntity
import sa.gheras.edutrack.data.entity.AssignmentStudentEntity
import sa.gheras.edutrack.data.entity.PendingWriteEntity
import sa.gheras.edutrack.data.entity.SubmissionEntity
import sa.gheras.edutrack.data.entity.SubmissionFileEntity
import sa.gheras.edutrack.data.remote.MeApi
import sa.gheras.edutrack.data.remote.dto.AssignmentFileDto
import sa.gheras.edutrack.data.remote.dto.CreateAssignmentRequest
import sa.gheras.edutrack.data.remote.dto.ReminderPreviewResponse
import sa.gheras.edutrack.data.remote.dto.ReviewSubmissionRequest
import sa.gheras.edutrack.data.remote.dto.SendReminderRequest
import sa.gheras.edutrack.data.remote.dto.SendReminderResponse
import sa.gheras.edutrack.data.remote.dto.SubmissionDto
import sa.gheras.edutrack.data.remote.dto.SubmitAssignmentRequest
import sa.gheras.edutrack.data.remote.dto.TeacherSubmissionsResponse
import sa.gheras.edutrack.data.repo.mappers.AssignmentMappers
import sa.gheras.edutrack.sync.Outbox
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class AssignmentsRepository(
    private val assignmentDao: AssignmentDao,
    private val submissionDao: SubmissionDao,
    private val assignmentStudentDao: AssignmentStudentDao? = null,
    private val submissionFileDao: SubmissionFileDao? = null,
    private val meApi: MeApi? = null,
    private val outbox: Outbox? = null
) {
    fun observeAll(): Flow<List<AssignmentEntity>> = assignmentDao.observeAll()

    fun observeByStudent(studentId: String): Flow<List<AssignmentEntity>> =
        assignmentDao.observeByStudent(studentId)

    fun observePublishedByStudent(studentId: String): Flow<List<AssignmentEntity>> =
        assignmentDao.observePublishedByStudent(studentId)

    fun observeByTeacher(teacherUserId: String): Flow<List<AssignmentEntity>> =
        assignmentDao.observeByTeacher(teacherUserId)

    fun observeDraftsByTeacher(teacherUserId: String): Flow<List<AssignmentEntity>> =
        assignmentDao.observeDraftsByTeacher(teacherUserId)

    fun observePublishedByTeacher(teacherUserId: String): Flow<List<AssignmentEntity>> =
        assignmentDao.observePublishedByTeacher(teacherUserId)

    suspend fun getById(id: String): AssignmentEntity? = withContext(Dispatchers.IO) {
        assignmentDao.getById(id)
    }

    suspend fun getPublishedById(id: String): AssignmentEntity? = withContext(Dispatchers.IO) {
        assignmentDao.getPublishedById(id)
    }

    suspend fun createAssignment(
        title: String,
        subject: String?,
        dueDate: LocalDate? = null,
        instructions: String?,
        pageRef: String?,
        teacherUserId: String?,
        studentIds: List<String>,
        branchId: String? = null,
        roomId: String? = null,
        isDraft: Boolean = false,
        files: List<AssignmentFileDto> = emptyList()
    ): AssignmentEntity = withContext(Dispatchers.IO) {
        val now = Instant.now()
        val assignmentId = UUID.randomUUID().toString()
        val assignment = AssignmentEntity(
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
        // 1. Immediate optimistic local persistence in Room
        assignmentDao.upsert(assignment)
        if (studentIds.isNotEmpty() && assignmentStudentDao != null) {
            val links = studentIds.map { studentId ->
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
            assignmentStudentDao.upsertAll(links)
        }

        // 2. Enqueue in Outbox for background sync
        if (outbox != null) {
            val payload = JSONObject().apply {
                put("assignment_id", assignmentId)
                put("title", title)
                if (subject != null) put("subject", subject)
                if (roomId != null) put("room_id", roomId)
                if (dueDate != null) put("due_date", dueDate.toString())
                if (instructions != null) put("instructions", instructions)
                if (pageRef != null) put("page_ref", pageRef)
                if (teacherUserId != null) put("teacher_user_id", teacherUserId)
                if (branchId != null) put("branch_id", branchId)
                put("is_draft", isDraft)
                val arr = JSONArray()
                studentIds.forEach { arr.put(it) }
                put("student_ids", arr)
                if (files.isNotEmpty()) {
                    val fArr = JSONArray()
                    files.forEach { f ->
                        val fObj = JSONObject().apply {
                            if (f.storageKey != null) put("storage_key", f.storageKey)
                            if (f.mediaType != null) put("media_type", f.mediaType)
                            if (f.fileName != null) put("file_name", f.fileName)
                            if (f.bytes != null) put("bytes", f.bytes)
                        }
                        fArr.put(fObj)
                    }
                    put("files", fArr)
                }
            }.toString()

            val naturalKey = "assignment:$assignmentId"
            outbox.enqueue(
                kind = PendingWriteEntity.KIND_ASSIGNMENT_CREATE,
                naturalKey = naturalKey,
                payloadJson = payload
            )
        }
        assignment
    }

    suspend fun publishAssignment(assignmentId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val now = Instant.now()
        assignmentDao.markPublished(assignmentId, now)
        val api = meApi
        if (api != null) {
            try {
                val res = api.publishAssignment(assignmentId)
                if (res.isSuccessful) Result.success(Unit)
                else Result.failure(Exception("Failed to publish assignment: HTTP ${res.code()}"))
            } catch (e: Exception) {
                // If offline, local markPublished is already set. Outbox could sync if needed.
                Result.success(Unit)
            }
        } else {
            Result.success(Unit)
        }
    }

    fun observeSubmissions(assignmentId: String): Flow<List<SubmissionEntity>> =
        submissionDao.observeByAssignment(assignmentId)

    fun observeStudentSubmissions(studentId: String): Flow<List<SubmissionEntity>> =
        submissionDao.observeByStudent(studentId)

    fun observeLatestByStudent(studentId: String): Flow<List<SubmissionEntity>> =
        submissionDao.observeLatestByStudent(studentId)

    fun observeLatestByAssignment(assignmentId: String): Flow<List<SubmissionEntity>> =
        submissionDao.observeLatestByAssignment(assignmentId)

    suspend fun getLatestSubmission(assignmentId: String, studentId: String): SubmissionEntity? =
        withContext(Dispatchers.IO) {
            submissionDao.getLatestByAssignmentAndStudent(assignmentId, studentId)
        }

    fun observeSubmissionFiles(submissionId: String): Flow<List<SubmissionFileEntity>> =
        submissionFileDao?.observeBySubmission(submissionId) ?: kotlinx.coroutines.flow.flowOf(emptyList())

    fun observeAllSubmissionFiles(): Flow<List<SubmissionFileEntity>> =
        submissionFileDao?.observeAll() ?: kotlinx.coroutines.flow.flowOf(emptyList())

    fun observeSubmissionFilesByAssignment(assignmentId: String): Flow<List<SubmissionFileEntity>> =
        submissionFileDao?.observeByAssignment(assignmentId) ?: kotlinx.coroutines.flow.flowOf(emptyList())

    suspend fun getSubmissionForStudent(assignmentId: String, studentId: String): SubmissionEntity? =
        withContext(Dispatchers.IO) {
            submissionDao.getByAssignmentAndStudent(assignmentId, studentId)
        }

    suspend fun getSubmissionFiles(submissionId: String): List<SubmissionFileEntity> =
        withContext(Dispatchers.IO) {
            submissionFileDao?.getBySubmission(submissionId) ?: emptyList()
        }

    suspend fun submitAssignment(
        assignmentId: String,
        studentId: String,
        note: String? = null,
        files: List<String> = emptyList(),
        clientRequestId: String? = null,
        branchId: String? = null
    ): Result<SubmissionEntity> = withContext(Dispatchers.IO) {
        val now = Instant.now()
        val cReqId = clientRequestId ?: UUID.randomUUID().toString()
        val latest = submissionDao.getLatestByAssignmentAndStudent(assignmentId, studentId)
        val nextAttempt = (latest?.attemptNumber ?: 0) + 1
        val parentId = latest?.id

        val assignment = assignmentDao.getById(assignmentId)
        val today = LocalDate.now()
        val isLate = assignment?.dueDate != null && today.isAfter(assignment.dueDate)

        val localId = "local:${UUID.randomUUID()}"
        val localSubmission = SubmissionEntity(
            id = localId,
            branchId = branchId,
            assignmentId = assignmentId,
            studentId = studentId,
            attemptNumber = nextAttempt,
            parentSubmissionId = parentId,
            gradeLevel = null,
            revisionNote = null,
            teacherFeedbackKey = null,
            isLate = isLate,
            submittedAt = now,
            status = "submitted",
            grade = null,
            feedback = note,
            createdAt = now,
            updatedAt = now,
            deletedAt = null
        )

        // 1. Optimistic insert in Room
        submissionDao.upsert(localSubmission)
        if (files.isNotEmpty() && submissionFileDao != null) {
            val fileEntities = files.mapIndexed { idx, key ->
                SubmissionFileEntity(
                    id = "local:$localId:$idx",
                    branchId = branchId,
                    submissionId = localId,
                    storageKey = key,
                    width = null,
                    height = null,
                    bytes = null,
                    sha256 = null,
                    createdAt = now,
                    updatedAt = now
                )
            }
            submissionFileDao.upsertAll(fileEntities)
        }

        // 2. Try network call if online
        val api = meApi
        if (api != null) {
            try {
                val req = SubmitAssignmentRequest(
                    studentId = studentId,
                    note = note,
                    files = files,
                    clientRequestId = cReqId
                )
                val dto = api.submitAssignment(assignmentId, req)
                val syncedEntity = AssignmentMappers.submissionToEntity(dto, branchId)
                if (localId != syncedEntity.id) {
                    submissionFileDao?.deleteBySubmission(localId)
                    submissionDao.deleteById(localId)
                }
                submissionDao.upsert(syncedEntity)
                if (submissionFileDao != null && dto.files.isNotEmpty()) {
                    val fileEntities = AssignmentMappers.submissionFilesToEntities(dto, branchId)
                    submissionFileDao.replaceScope(syncedEntity.id, fileEntities)
                }
                Result.success(syncedEntity)
            } catch (e: Exception) {
                // Network failed -> persist to Outbox
                if (outbox != null) {
                    val payload = JSONObject().apply {
                        put("submission_id", localId)
                        put("assignment_id", assignmentId)
                        put("student_id", studentId)
                        put("attempt_number", nextAttempt)
                        put("parent_submission_id", parentId)
                        put("is_late", isLate)
                        if (note != null) put("note", note)
                        if (branchId != null) put("branch_id", branchId)
                        val filesArr = JSONArray()
                        files.forEach { filesArr.put(it) }
                        put("files", filesArr)
                        put("client_request_id", cReqId)
                    }.toString()

                    outbox.enqueue(
                        kind = PendingWriteEntity.KIND_SUBMISSION_CREATE,
                        naturalKey = "submission:$assignmentId:$studentId:$cReqId",
                        payloadJson = payload
                    )
                }
                Result.success(localSubmission)
            }
        } else {
            Result.success(localSubmission)
        }
    }

    suspend fun reviewSubmission(
        submissionId: String,
        action: String,
        gradeLevel: String? = null,
        revisionNote: String? = null,
        teacherFeedbackKey: String? = null
    ): Result<SubmissionEntity> = withContext(Dispatchers.IO) {
        val status = if (action == "grade") "graded" else "revision_requested"
        val api = meApi ?: return@withContext Result.failure(IllegalStateException("MeApi is not configured"))
        try {
            val req = ReviewSubmissionRequest(
                status = status,
                gradeLevel = gradeLevel,
                revisionNote = revisionNote,
                teacherFeedbackKey = teacherFeedbackKey
            )
            val dto = api.reviewSubmission(submissionId, req)
            val entity = AssignmentMappers.submissionToEntity(dto)
            submissionDao.upsert(entity)
            Result.success(entity)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAssignmentSubmissions(assignmentId: String): Result<TeacherSubmissionsResponse> =
        withContext(Dispatchers.IO) {
            val api = meApi ?: return@withContext Result.failure(IllegalStateException("MeApi is not configured"))
            try {
                val res = api.getAssignmentSubmissions(assignmentId)
                val entities = res.items.map { AssignmentMappers.submissionToEntity(it) }
                submissionDao.upsertAll(entities)
                Result.success(res)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun previewReminders(assignmentId: String, kind: String? = null): Result<ReminderPreviewResponse> =
        withContext(Dispatchers.IO) {
            val api = meApi ?: return@withContext Result.failure(IllegalStateException("MeApi is not configured"))
            try {
                val res = api.previewAssignmentReminders(assignmentId, kind)
                Result.success(res)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun sendReminders(assignmentId: String, kind: String? = null, studentId: String? = null): Result<SendReminderResponse> =
        withContext(Dispatchers.IO) {
            val api = meApi ?: return@withContext Result.failure(IllegalStateException("MeApi is not configured"))
            try {
                val res = api.sendAssignmentReminders(
                    assignmentId,
                    SendReminderRequest(kind = kind, studentId = studentId)
                )
                Result.success(res)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun submitHomework(
        assignmentId: String,
        studentId: String,
        imageBytesList: List<ByteArray>,
        notes: String? = null
    ): Result<SubmissionDto> {
        val api = meApi ?: return Result.failure(IllegalStateException("MeApi is not configured"))
        return withContext(Dispatchers.IO) {
            try {
                val assignmentIdBody = assignmentId.toRequestBody("text/plain".toMediaTypeOrNull())
                val studentIdBody = studentId.toRequestBody("text/plain".toMediaTypeOrNull())
                val notesBody = notes?.toRequestBody("text/plain".toMediaTypeOrNull())

                val fileParts = imageBytesList.mapIndexed { index, bytes ->
                    val requestFile = bytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
                    MultipartBody.Part.createFormData(
                        name = "files",
                        filename = "page_${index + 1}.jpg",
                        body = requestFile
                    )
                }

                val submissionDto = api.submitHomework(
                    assignmentId = assignmentIdBody,
                    studentId = studentIdBody,
                    notes = notesBody,
                    files = fileParts
                )

                // Cache locally in Room
                val submissionEntity = AssignmentMappers.submissionToEntity(submissionDto)
                submissionDao.upsert(submissionEntity)
                if (submissionFileDao != null && submissionDto.files.isNotEmpty()) {
                    val fileEntities = AssignmentMappers.submissionFilesToEntities(submissionDto)
                    submissionFileDao.replaceScope(submissionEntity.id, fileEntities)
                }

                Result.success(submissionDto)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun gradeSubmission(
        submissionId: String,
        grade: Double,
        feedback: String? = null
    ): Result<SubmissionDto> {
        val api = meApi ?: return Result.failure(IllegalStateException("MeApi is not configured"))
        return withContext(Dispatchers.IO) {
            try {
                val body = sa.gheras.edutrack.data.remote.dto.GradeSubmissionBody(
                    grade = grade,
                    feedback = feedback
                )
                val submissionDto = api.gradeSubmission(submissionId, body)
                val submissionEntity = AssignmentMappers.submissionToEntity(submissionDto)
                submissionDao.upsert(submissionEntity)
                Result.success(submissionDto)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
}
