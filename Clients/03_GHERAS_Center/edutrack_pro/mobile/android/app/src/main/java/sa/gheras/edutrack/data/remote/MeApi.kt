package sa.gheras.edutrack.data.remote

import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
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
import sa.gheras.edutrack.data.remote.dto.ProfileDto
import sa.gheras.edutrack.data.remote.dto.ReceiptDto
import sa.gheras.edutrack.data.remote.dto.RoomDto
import sa.gheras.edutrack.data.remote.dto.ScheduleDto
import sa.gheras.edutrack.data.remote.dto.SkillProgressDto
import sa.gheras.edutrack.data.remote.dto.StudentDto
import sa.gheras.edutrack.data.remote.dto.UploadResponseDto
import sa.gheras.edutrack.data.remote.dto.SubmissionDto

interface MeApi {

    @GET("me/profile")
    suspend fun profile(): ProfileDto

    @GET("me/students")
    suspend fun listStudents(
        @Query("limit") limit: Int = 500,
        @Query("offset") offset: Int = 0,
        @Query("status") status: String? = null
    ): Envelope<StudentDto>

    @GET("me/rooms")
    suspend fun listRooms(
        @Query("limit") limit: Int = 500,
        @Query("offset") offset: Int = 0
    ): Envelope<RoomDto>

    @GET("me/schedule")
    suspend fun listSchedule(
        @Query("limit") limit: Int = 500,
        @Query("offset") offset: Int = 0,
        @Query("day") day: String? = null
    ): Envelope<ScheduleDto>

    @GET("me/notifications")
    suspend fun listNotifications(
        @Query("limit") limit: Int = 500,
        @Query("offset") offset: Int = 0,
        @Query("unread") unread: Boolean? = null
    ): Envelope<NotificationDto>

    @POST("me/notifications/{id}/read")
    suspend fun markNotificationRead(@Path("id") id: String): NotificationDto

    @POST("me/notifications/broadcast")
    suspend fun postBroadcast(@Body body: BroadcastBody): BroadcastResultDto

    @DELETE("me/notifications/{id}")
    suspend fun deleteNotification(@Path("id") id: String): Response<Unit>

    @POST("me/notifications/clear-read")
    suspend fun clearReadNotifications(@Body body: ClearReadBody): Response<Unit>

    @GET("me/attendance")
    suspend fun listAttendance(
        @Query("limit") limit: Int = 500,
        @Query("offset") offset: Int = 0,
        @Query("student_id") studentId: String? = null,
        @Query("date_from") dateFrom: String? = null,
        @Query("date_to") dateTo: String? = null
    ): Envelope<AttendanceDto>

    @GET("me/evaluations")
    suspend fun listEvaluations(
        @Query("limit") limit: Int = 500,
        @Query("offset") offset: Int = 0,
        @Query("student_id") studentId: String? = null,
        @Query("date_from") dateFrom: String? = null,
        @Query("date_to") dateTo: String? = null
    ): Envelope<EvaluationDto>

    @GET("me/assignments")
    suspend fun listAssignments(
        @Query("limit") limit: Int = 500,
        @Query("offset") offset: Int = 0,
        @Query("student_id") studentId: String? = null,
        @Query("due_from") dueFrom: String? = null,
        @Query("due_to") dueTo: String? = null
    ): Envelope<AssignmentDto>

    @POST("me/assignments")
    suspend fun postAssignment(@Body body: CreateAssignmentBody): AssignmentDto

    @GET("me/submissions")
    suspend fun listSubmissions(
        @Query("limit") limit: Int = 500,
        @Query("offset") offset: Int = 0,
        @Query("assignment_id") assignmentId: String? = null,
        @Query("student_id") studentId: String? = null
    ): Envelope<SubmissionDto>

    @GET("me/lesson-logs")
    suspend fun listLessonLogs(
        @Query("limit") limit: Int = 500,
        @Query("offset") offset: Int = 0,
        @Query("schedule_id") scheduleId: String? = null,
        @Query("date_from") dateFrom: String? = null,
        @Query("date_to") dateTo: String? = null
    ): Envelope<LessonLogDto>

    @POST("me/lesson-logs")
    suspend fun postLessonLog(@Body body: LessonLogBody): LessonLogDto

    @GET("me/skill-progress")
    suspend fun listSkillProgress(
        @Query("limit") limit: Int = 500,
        @Query("offset") offset: Int = 0,
        @Query("student_id") studentId: String? = null,
        @Query("subject") subject: String? = null
    ): Envelope<SkillProgressDto>

    @GET("me/installments")
    suspend fun listInstallments(
        @Query("limit") limit: Int = 500,
        @Query("offset") offset: Int = 0,
        @Query("student_id") studentId: String? = null,
        @Query("status") status: String? = null
    ): Envelope<InstallmentDto>

    @GET("me/receipts")
    suspend fun listReceipts(
        @Query("limit") limit: Int = 500,
        @Query("offset") offset: Int = 0,
        @Query("student_id") studentId: String? = null
    ): Envelope<ReceiptDto>

    @POST("attendance/students")
    suspend fun postAttendanceBatch(@Body items: List<AttendanceItemBody>): Response<Unit>

    @POST("evaluations/daily")
    suspend fun postDailyEvaluationsBatch(@Body items: List<DailyEvalItemBody>): Response<Unit>

    @POST("me/uploads")
    suspend fun uploadAttachment(
        @Header("X-Filename") filename: String,
        @Body body: RequestBody
    ): UploadResponseDto
}
