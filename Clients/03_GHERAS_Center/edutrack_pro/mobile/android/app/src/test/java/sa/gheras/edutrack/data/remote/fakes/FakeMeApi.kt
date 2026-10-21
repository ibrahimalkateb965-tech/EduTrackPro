package sa.gheras.edutrack.data.remote.fakes

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import sa.gheras.edutrack.data.remote.MeApi
import sa.gheras.edutrack.data.remote.dto.UploadResponseDto
import sa.gheras.edutrack.data.remote.dto.GradeSubmissionBody
import sa.gheras.edutrack.data.remote.dto.AttendanceDto
import sa.gheras.edutrack.data.remote.dto.AttendanceItemBody
import sa.gheras.edutrack.data.remote.dto.AssignmentDto
import sa.gheras.edutrack.data.remote.dto.CreateAssignmentRequest
import sa.gheras.edutrack.data.remote.dto.ReminderPreviewResponse
import sa.gheras.edutrack.data.remote.dto.ReminderRecipientDto
import sa.gheras.edutrack.data.remote.dto.ReviewSubmissionRequest
import sa.gheras.edutrack.data.remote.dto.SendReminderRequest
import sa.gheras.edutrack.data.remote.dto.SendReminderResponse
import sa.gheras.edutrack.data.remote.dto.SubmitAssignmentRequest
import sa.gheras.edutrack.data.remote.dto.TeacherAssignmentStatsDto
import sa.gheras.edutrack.data.remote.dto.TeacherSubmissionsResponse
import sa.gheras.edutrack.data.remote.dto.BroadcastBody
import sa.gheras.edutrack.data.remote.dto.BroadcastResultDto
import sa.gheras.edutrack.data.remote.dto.ClearReadBody
import sa.gheras.edutrack.data.remote.dto.CreateAssignmentBody
import sa.gheras.edutrack.data.remote.dto.DailyEvalItemBody
import sa.gheras.edutrack.data.remote.dto.Envelope
import sa.gheras.edutrack.data.remote.dto.EvaluationDto
import sa.gheras.edutrack.data.remote.dto.InstallmentDto
import sa.gheras.edutrack.data.remote.dto.LessonLogBody
import sa.gheras.edutrack.data.remote.dto.LessonLogDto
import sa.gheras.edutrack.data.remote.dto.NotificationDto
import sa.gheras.edutrack.data.remote.dto.PickupDelegatesResponse
import sa.gheras.edutrack.data.remote.dto.ProfileCenterDto
import sa.gheras.edutrack.data.remote.dto.ProfileDto
import sa.gheras.edutrack.data.remote.dto.ProfileScopeDto
import sa.gheras.edutrack.data.remote.dto.ProfileUserDto
import sa.gheras.edutrack.data.remote.dto.ReceiptDto
import sa.gheras.edutrack.data.remote.dto.RoomDto
import sa.gheras.edutrack.data.remote.dto.ScheduleDto
import sa.gheras.edutrack.data.remote.dto.SkillProgressDto
import sa.gheras.edutrack.data.remote.dto.StudentDto
import sa.gheras.edutrack.data.remote.dto.SubmissionDto

class FakeMeApi : MeApi {
    override suspend fun profile(): ProfileDto {
        return ProfileDto(
            user = ProfileUserDto("user_1", "teacher1", "teacher", "Teacher One"),
            scope = ProfileScopeDto(listOf("room_1"), listOf("student_1")),
            center = ProfileCenterDto("Gheras Center", "0500000000")
        )
    }

    override suspend fun listStudents(limit: Int, offset: Int, status: String?) = Envelope<StudentDto>()
    override suspend fun listRooms(limit: Int, offset: Int) = Envelope<RoomDto>()
    override suspend fun listSchedule(limit: Int, offset: Int, day: String?) = Envelope<ScheduleDto>()
    override suspend fun listNotifications(limit: Int, offset: Int, unread: Boolean?) = Envelope<NotificationDto>()
    override suspend fun markNotificationRead(id: String) = NotificationDto(id = id, title = "Title", body = "Body", readAt = "2026-09-21T08:00:00Z")
    override suspend fun postBroadcast(body: BroadcastBody) = BroadcastResultDto(body.id, recipientCount = 1, createdAt = "2026-09-21T08:00:00Z")
    override suspend fun deleteNotification(id: String) = Response.success(Unit)
    override suspend fun clearReadNotifications(body: ClearReadBody) = Response.success(Unit)
    override suspend fun listAttendance(limit: Int, offset: Int, studentId: String?, dateFrom: String?, dateTo: String?) = Envelope<AttendanceDto>()
    override suspend fun listEvaluations(limit: Int, offset: Int, studentId: String?, dateFrom: String?, dateTo: String?) = Envelope<EvaluationDto>()
    override suspend fun listAssignments(limit: Int, offset: Int, studentId: String?, dueFrom: String?, dueTo: String?) = Envelope<AssignmentDto>()
    override suspend fun listSubmissions(limit: Int, offset: Int, assignmentId: String?, studentId: String?) = Envelope<SubmissionDto>()
    override suspend fun listLessonLogs(limit: Int, offset: Int, scheduleId: String?, dateFrom: String?, dateTo: String?) = Envelope<LessonLogDto>()
    override suspend fun postLessonLog(body: LessonLogBody) = LessonLogDto("log_1", null, body.scheduleId, body.date, body.status)
    override suspend fun listSkillProgress(limit: Int, offset: Int, studentId: String?, subject: String?) = Envelope<SkillProgressDto>()
    override suspend fun listInstallments(limit: Int, offset: Int, studentId: String?, status: String?) = Envelope<InstallmentDto>()
    override suspend fun listReceipts(limit: Int, offset: Int, studentId: String?) = Envelope<ReceiptDto>()
    override suspend fun listPickupDelegates(): PickupDelegatesResponse = PickupDelegatesResponse(emptyList())
    override suspend fun postAttendanceBatch(items: List<AttendanceItemBody>) = Response.success(Unit)
    override suspend fun postDailyEvaluationsBatch(items: List<DailyEvalItemBody>) = Response.success(Unit)
    override suspend fun postAssignment(body: CreateAssignmentBody) = AssignmentDto(
        id = body.id ?: "assign_fake",
        title = body.title,
        dueDate = body.dueDate,
        instructions = body.instructions,
        pageRef = body.pageRef,
        studentIds = body.studentIds
    )

    override suspend fun uploadAttachment(filename: String, body: RequestBody): UploadResponseDto {
        return UploadResponseDto(
            url = "https://example.com/uploads/$filename",
            filename = filename,
            size = 1024L
        )
    }

    override suspend fun submitHomework(
        assignmentId: RequestBody,
        studentId: RequestBody,
        notes: RequestBody?,
        files: List<MultipartBody.Part>
    ): SubmissionDto {
        return SubmissionDto(
            id = "sub_fake",
            assignmentId = "assign_fake",
            studentId = "student_fake",
            status = "submitted"
        )
    }

    var gradeAssignmentId: String = "a1"
    var gradeStudentId: String = "s1"

    override suspend fun gradeSubmission(
        submissionId: String,
        body: GradeSubmissionBody
    ): SubmissionDto {
        return SubmissionDto(
            id = submissionId,
            assignmentId = gradeAssignmentId,
            studentId = gradeStudentId,
            status = "graded",
            grade = body.grade,
            feedback = body.feedback
        )
    }

    override suspend fun createAssignmentModern(body: CreateAssignmentRequest): AssignmentDto {
        return AssignmentDto(
            id = "assign_modern_1",
            title = body.title,
            subject = body.subject,
            kind = body.kind,
            dueDate = body.dueDate,
            instructions = body.instructions,
            pageRef = body.pageRef,
            isDraft = body.isDraft,
            studentIds = body.studentIds
        )
    }

    override suspend fun publishAssignment(id: String): Response<Unit> {
        return Response.success(Unit)
    }

    override suspend fun getAssignmentSubmissions(id: String): TeacherSubmissionsResponse {
        return TeacherSubmissionsResponse(
            stats = TeacherAssignmentStatsDto(total = 1, pending = 0, submitted = 1, graded = 0, revisionRequested = 0),
            items = listOf(
                SubmissionDto(
                    id = "sub_teacher_1",
                    assignmentId = id,
                    studentId = "student_1",
                    attemptNumber = 1,
                    status = "submitted"
                )
            )
        )
    }

    val submissionsStore = mutableMapOf<String, SubmissionDto>()

    override suspend fun submitAssignment(id: String, body: SubmitAssignmentRequest): SubmissionDto {
        val subId = "sub_submit_${submissionsStore.size + 1}"
        val prevAttempts = submissionsStore.values.count { it.assignmentId == id && it.studentId == body.studentId }
        val attemptNo = prevAttempts + 1
        val parentId = submissionsStore.values.findLast { it.assignmentId == id && it.studentId == body.studentId }?.id
        val dto = SubmissionDto(
            id = subId,
            assignmentId = id,
            studentId = body.studentId,
            attemptNumber = attemptNo,
            parentSubmissionId = parentId,
            isLate = true,
            status = "submitted",
            feedback = body.note
        )
        submissionsStore[subId] = dto
        return dto
    }

    override suspend fun reviewSubmission(id: String, body: ReviewSubmissionRequest): SubmissionDto {
        val existing = submissionsStore[id]
        val updated = SubmissionDto(
            id = id,
            assignmentId = existing?.assignmentId ?: "asgn_1",
            studentId = existing?.studentId ?: "student_1",
            attemptNumber = existing?.attemptNumber ?: 1,
            parentSubmissionId = existing?.parentSubmissionId,
            isLate = existing?.isLate ?: true,
            status = body.status,
            gradeLevel = body.gradeLevel,
            grade = body.grade,
            feedback = body.feedback,
            revisionNote = body.revisionNote,
            teacherFeedbackKey = body.teacherFeedbackKey
        )
        submissionsStore[id] = updated
        return updated
    }

    override suspend fun previewAssignmentReminders(id: String, kind: String?): ReminderPreviewResponse {
        return ReminderPreviewResponse(
            count = 1,
            students = listOf(
                ReminderRecipientDto(
                    studentId = "student_1",
                    studentName = "Zaid",
                    status = kind ?: "pending"
                )
            )
        )
    }

    override suspend fun sendAssignmentReminders(id: String, body: SendReminderRequest): SendReminderResponse {
        return SendReminderResponse(
            sent = 1,
            cooldownRemainingSeconds = null,
            recipients = listOf(
                ReminderRecipientDto(
                    studentId = body.studentId ?: "student_1",
                    studentName = "Zaid",
                    status = body.kind ?: "pending"
                )
            )
        )
    }

    override suspend fun uploadMedia(filename: String, body: RequestBody): UploadResponseDto {
        return UploadResponseDto(
            storageKey = "uploads/$filename",
            url = "https://example.com/api/v1/me/files/uploads/$filename",
            filename = filename,
            bytes = 1024L
        )
    }

    override suspend fun uploadMediaMultipart(file: MultipartBody.Part): UploadResponseDto {
        return UploadResponseDto(
            storageKey = "uploads/multipart_file",
            url = "https://example.com/api/v1/me/files/uploads/multipart_file",
            filename = "multipart_file",
            bytes = 1024L
        )
    }
}

