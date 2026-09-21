package sa.gheras.edutrack.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LessonLogDto(
    val id: String,
    @SerialName("branch_id") val branchId: String? = null,
    @SerialName("schedule_id") val scheduleId: String,
    val date: String,
    val status: String,
    val covered: String? = null,
    val homework: String? = null,
    @SerialName("teacher_user_id") val teacherUserId: String? = null,
    val notes: String? = null,
    @SerialName("room_id") val roomId: String? = null,
    val day: String? = null,
    @SerialName("start_time") val startTime: String? = null,
    @SerialName("end_time") val endTime: String? = null,
    val subject: String? = null,
    @SerialName("room_name") val roomName: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)
