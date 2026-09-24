package sa.gheras.edutrack.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class NotificationDto(
    val id: String,
    @SerialName("branch_id") val branchId: String? = null,
    val kind: String = "announcement",
    val title: String,
    val body: String? = null,
    @SerialName("target_type") val targetType: String? = null,
    @SerialName("target_id") val targetId: String? = null,
    val priority: String = "normal",
    @SerialName("action_url") val actionUrl: String? = null,
    @SerialName("sender_user_id") val senderUserId: String? = null,
    @SerialName("sender_name") val senderName: String? = null,
    @SerialName("broadcast_id") val broadcastId: String? = null,
    @SerialName("read_at") val readAt: String? = null,
    @SerialName("sent_at") val sentAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class BroadcastBody(
    val id: String,
    val title: String,
    val body: String? = null,
    val priority: String = "normal",
    @SerialName("room_id") val roomId: String? = null,
    @SerialName("student_ids") val studentIds: List<String> = emptyList(),
    @SerialName("include_guardians") val includeGuardians: Boolean = true,
    @SerialName("include_students") val includeStudents: Boolean = true
)

@Serializable
data class BroadcastResultDto(
    val id: String,
    @SerialName("recipient_count") val recipientCount: Int,
    @SerialName("created_at") val createdAt: String
)

@Serializable
data class ClearReadBody(
    val ids: List<String>
)
