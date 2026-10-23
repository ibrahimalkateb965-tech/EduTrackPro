package sa.gheras.edutrack.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SubjectRatingDto(
    val subject: String,
    val rating: String,
    val notes: String? = null
)

@Serializable
data class AttendanceStatsDto(
    @SerialName("total_days") val totalDays: Int = 0,
    @SerialName("present_days") val presentDays: Int = 0,
    @SerialName("absent_days") val absentDays: Int = 0,
    @SerialName("excused_absent_days") val excusedAbsentDays: Int = 0,
    @SerialName("late_days") val lateDays: Int = 0,
    @SerialName("attendance_rate") val attendanceRate: Double = 0.0
)

@Serializable
data class MonthlyReportDto(
    val id: String,
    @SerialName("branch_id") val branchId: String? = null,
    @SerialName("student_id") val studentId: String,
    @SerialName("student_name") val studentName: String? = null,
    @SerialName("room_id") val roomId: String,
    @SerialName("room_name") val roomName: String? = null,
    val month: String,
    @SerialName("teacher_user_id") val teacherUserId: String? = null,
    @SerialName("teacher_name") val teacherName: String? = null,
    val status: String = "draft",
    @SerialName("attendance_stats") val attendanceStats: AttendanceStatsDto? = null,
    @SerialName("subject_ratings") val subjectRatings: List<SubjectRatingDto> = emptyList(),
    val strengths: String? = null,
    val improvements: String? = null,
    val recommendations: String? = null,
    @SerialName("revision_notes") val revisionNotes: String? = null,
    @SerialName("submitted_at") val submittedAt: String? = null,
    @SerialName("reviewed_by_user_id") val reviewedByUserId: String? = null,
    @SerialName("reviewed_at") val reviewedAt: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class UpsertMonthlyReportRequest(
    @SerialName("student_id") val studentId: String,
    @SerialName("room_id") val roomId: String,
    val month: String,
    @SerialName("attendance_stats") val attendanceStats: AttendanceStatsDto = AttendanceStatsDto(),
    @SerialName("subject_ratings") val subjectRatings: List<SubjectRatingDto> = emptyList(),
    val strengths: String? = null,
    val improvements: String? = null,
    val recommendations: String? = null,
    val action: String = "save_draft"
)

@Serializable
data class ReviewReportRequest(
    val action: String,
    @SerialName("revision_notes") val revisionNotes: String? = null
)

@Serializable
data class BatchPublishRequest(
    @SerialName("room_id") val roomId: String,
    val month: String
)

@Serializable
data class ReportPdfResponse(
    @SerialName("report_id") val reportId: String,
    @SerialName("student_name") val studentName: String? = null,
    val month: String,
    @SerialName("pdf_url") val pdfUrl: String,
    val status: String
)
