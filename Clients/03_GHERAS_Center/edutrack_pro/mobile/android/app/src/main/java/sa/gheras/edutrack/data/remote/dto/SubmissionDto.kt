package sa.gheras.edutrack.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SubmissionFileDto(
    val id: String,
    @SerialName("storage_key") val storageKey: String? = null,
    val url: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
    @SerialName("file_name") val fileName: String? = null,
    val bytes: Long? = null,
    val width: Int? = null,
    val height: Int? = null
)

@Serializable
data class SubmissionDto(
    val id: String,
    @SerialName("assignment_id") val assignmentId: String,
    @SerialName("student_id") val studentId: String,
    @SerialName("attempt_number") val attemptNumber: Int = 1,
    @SerialName("parent_submission_id") val parentSubmissionId: String? = null,
    @SerialName("grade_level") val gradeLevel: String? = null,
    @SerialName("revision_note") val revisionNote: String? = null,
    @SerialName("teacher_feedback_key") val teacherFeedbackKey: String? = null,
    @SerialName("is_late") val isLate: Boolean = false,
    val status: String? = null,
    @SerialName("submitted_at") val submittedAt: String? = null,
    @SerialName("assignment_title") val assignmentTitle: String? = null,
    @SerialName("student_name") val studentName: String? = null,
    val grade: Double? = null,
    val feedback: String? = null,
    val files: List<SubmissionFileDto> = emptyList(),
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("deleted_at") val deletedAt: String? = null
)

@Serializable
data class SubmitAssignmentRequest(
    @SerialName("student_id") val studentId: String,
    val note: String? = null,
    val files: List<String> = emptyList(),
    @SerialName("client_request_id") val clientRequestId: String? = null
)

@Serializable
data class ReviewSubmissionRequest(
    val status: String,
    @SerialName("grade_level") val gradeLevel: String? = null,
    val grade: Double? = null,
    val feedback: String? = null,
    @SerialName("teacher_feedback_key") val teacherFeedbackKey: String? = null,
    @SerialName("revision_note") val revisionNote: String? = null
)

@Serializable
data class TeacherAssignmentStatsDto(
    val total: Int = 0,
    val pending: Int = 0,
    val submitted: Int = 0,
    val graded: Int = 0,
    @SerialName("revision_requested") val revisionRequested: Int = 0
)

@Serializable
data class TeacherSubmissionsResponse(
    val stats: TeacherAssignmentStatsDto? = null,
    val items: List<SubmissionDto> = emptyList()
)

@Serializable
data class GradeSubmissionBody(
    val grade: Double,
    val feedback: String? = null
)
