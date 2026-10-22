package sa.gheras.edutrack.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AttendanceExcuseDto(
    val id: String,
    @SerialName("branch_id") val branchId: String? = null,
    @SerialName("student_id") val studentId: String,
    @SerialName("student_name") val studentName: String? = null,
    @SerialName("room_id") val roomId: String? = null,
    @SerialName("room_name") val roomName: String? = null,
    @SerialName("excuse_date") val excuseDate: String,
    @SerialName("reason_text") val reasonText: String,
    @SerialName("attachment_storage_key") val attachmentStorageKey: String? = null,
    @SerialName("attachment_url") val attachmentUrl: String? = null,
    val status: String = "pending",
    @SerialName("supervisor_notes") val supervisorNotes: String? = null,
    @SerialName("reviewed_by_user_id") val reviewedByUserId: String? = null,
    @SerialName("reviewed_by_name") val reviewedByName: String? = null,
    @SerialName("reviewed_at") val reviewedAt: String? = null,
    @SerialName("submitted_by_user_id") val submittedByUserId: String? = null,
    @SerialName("submitted_by_name") val submittedByName: String? = null,
    @SerialName("client_request_id") val clientRequestId: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("deleted_at") val deletedAt: String? = null
)
