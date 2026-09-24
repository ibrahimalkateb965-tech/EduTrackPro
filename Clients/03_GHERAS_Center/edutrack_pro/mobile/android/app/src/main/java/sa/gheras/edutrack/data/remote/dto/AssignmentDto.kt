package sa.gheras.edutrack.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    @SerialName("due_date") val dueDate: String,
    @SerialName("student_ids") val studentIds: List<String> = emptyList(),
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("deleted_at") val deletedAt: String? = null
)
