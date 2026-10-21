package sa.gheras.edutrack.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AssignmentFileDto(
    val id: String? = null,
    @SerialName("assignment_id") val assignmentId: String? = null,
    @SerialName("storage_key") val storageKey: String? = null,
    val url: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
    val kind: String? = null,
    @SerialName("file_name") val fileName: String? = null,
    val bytes: Long? = null,
    @SerialName("duration_seconds") val durationSeconds: Int? = null,
    @SerialName("created_at") val createdAt: String? = null
)

@Serializable
data class AssignmentDto(
    val id: String,
    @SerialName("schedule_id") val scheduleId: String? = null,
    @SerialName("teacher_user_id") val teacherUserId: String? = null,
    val title: String,
    val subject: String? = null,
    val kind: String? = null,
    val description: String? = null,
    val instructions: String? = null,
    @SerialName("page_ref") val pageRef: String? = null,
    @SerialName("due_date") val dueDate: String? = null,
    @SerialName("is_draft") val isDraft: Boolean = false,
    @SerialName("display_status") val displayStatus: String? = null,
    @SerialName("teacher_name") val teacherName: String? = null,
    val files: List<AssignmentFileDto> = emptyList(),
    @SerialName("student_ids") val studentIds: List<String> = emptyList(),
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("deleted_at") val deletedAt: String? = null
)

@Serializable
data class CreateAssignmentRequest(
    val id: String? = null,
    val title: String,
    val subject: String? = null,
    val kind: String = "homework",
    @SerialName("room_id") val roomId: String? = null,
    @SerialName("due_date") val dueDate: String? = null,
    val instructions: String? = null,
    @SerialName("page_ref") val pageRef: String? = null,
    @SerialName("student_ids") val studentIds: List<String> = emptyList(),
    @SerialName("is_draft") val isDraft: Boolean = false,
    val files: List<AssignmentFileDto> = emptyList()
)

@Serializable
data class ReminderRecipientDto(
    @SerialName("student_id") val studentId: String,
    @SerialName("student_name") val studentName: String,
    @SerialName("guardian_phone") val guardianPhone: String? = null,
    val status: String
)

@Serializable
data class ReminderPreviewResponse(
    val count: Int = 0,
    val students: List<ReminderRecipientDto> = emptyList()
)

@Serializable
data class SendReminderRequest(
    val kind: String? = null,
    @SerialName("student_id") val studentId: String? = null,
    @SerialName("dry_run") val dryRun: Boolean = false
)

@Serializable
data class SendReminderResponse(
    val sent: Int = 0,
    @SerialName("cooldown_remaining_seconds") val cooldownRemainingSeconds: Int? = null,
    val recipients: List<ReminderRecipientDto> = emptyList()
)
