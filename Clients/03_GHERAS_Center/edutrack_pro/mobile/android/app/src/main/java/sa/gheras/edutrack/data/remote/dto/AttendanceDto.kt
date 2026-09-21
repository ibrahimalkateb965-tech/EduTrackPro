package sa.gheras.edutrack.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AttendanceDto(
    val id: String,
    @SerialName("student_id") val studentId: String,
    @SerialName("schedule_id") val scheduleId: String? = null,
    val date: String,
    val status: String,
    @SerialName("late_minutes") val lateMinutes: Int? = null,
    @SerialName("excuse_note") val excuseNote: String? = null,
    @SerialName("recorded_by_user_id") val recordedByUserId: String? = null,
    @SerialName("student_name") val studentName: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("deleted_at") val deletedAt: String? = null
)
