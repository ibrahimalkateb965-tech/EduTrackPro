package sa.gheras.edutrack.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SubmissionFileDto(
    val id: String,
    @SerialName("storage_key") val storageKey: String? = null,
    val url: String? = null,
    val width: Int? = null,
    val height: Int? = null
)

@Serializable
data class SubmissionDto(
    val id: String,
    @SerialName("assignment_id") val assignmentId: String,
    @SerialName("student_id") val studentId: String,
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
data class GradeSubmissionBody(
    val grade: Double,
    val feedback: String? = null
)
