package sa.gheras.edutrack.data.remote.fakes

import okhttp3.RequestBody
import retrofit2.Response
import sa.gheras.edutrack.data.remote.MeApi
import sa.gheras.edutrack.data.remote.dto.UploadResponseDto
import sa.gheras.edutrack.data.remote.dto.AttendanceDto
import sa.gheras.edutrack.data.remote.dto.AttendanceItemBody
import sa.gheras.edutrack.data.remote.dto.AssignmentDto
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
}
