package sa.gheras.edutrack.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class EvaluationDto(
    val id: String,
    @SerialName("student_id") val studentId: String,
    @SerialName("schedule_id") val scheduleId: String? = null,
    @SerialName("eval_type") val evalType: String? = null,
    val date: String,
    val score: Double? = null,
    val notes: String? = null,
    @SerialName("recorded_by_user_id") val recordedByUserId: String? = null,
    @SerialName("student_name") val studentName: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("deleted_at") val deletedAt: String? = null,
    val subject: String? = null,
    val value: Double? = null,
    @SerialName("branch_id") val branchId: String? = null,
    @SerialName("teacher_user_id") val teacherUserId: String? = null
)
