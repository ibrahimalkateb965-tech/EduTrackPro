package sa.gheras.edutrack.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ScheduleDto(
    val id: String,
    @SerialName("branch_id") val branchId: String? = null,
    @SerialName("room_id") val roomId: String,
    @SerialName("teacher_user_id") val teacherUserId: String? = null,
    val day: String,
    @SerialName("start_time") val startTime: String,
    @SerialName("end_time") val endTime: String,
    val subject: String,
    @SerialName("group_name") val groupName: String? = null,
    @SerialName("room_name") val roomName: String? = null,
    @SerialName("teacher_name") val teacherName: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("deleted_at") val deletedAt: String? = null
)
