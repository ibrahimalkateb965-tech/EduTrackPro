package sa.gheras.edutrack.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ThreadDto(
    val id: String,
    @SerialName("branch_id") val branchId: String? = null,
    @SerialName("student_id") val studentId: String,
    @SerialName("student_name") val studentName: String? = null,
    @SerialName("room_id") val roomId: String? = null,
    @SerialName("room_name") val roomName: String? = null,
    @SerialName("guardian_user_id") val guardianUserId: String,
    @SerialName("guardian_name") val guardianName: String? = null,
    @SerialName("staff_user_id") val staffUserId: String,
    @SerialName("staff_name") val staffName: String? = null,
    val subject: String,
    @SerialName("linked_assignment_id") val linkedAssignmentId: String? = null,
    @SerialName("linked_assignment_title") val linkedAssignmentTitle: String? = null,
    @SerialName("linked_summary_id") val linkedSummaryId: String? = null,
    @SerialName("linked_summary_title") val linkedSummaryTitle: String? = null,
    val status: String = "open",
    @SerialName("last_message_at") val lastMessageAt: String,
    @SerialName("last_message_preview") val lastMessagePreview: String? = null,
    @SerialName("unread_count") val unreadCount: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class ThreadMessageDto(
    val id: String,
    @SerialName("branch_id") val branchId: String? = null,
    @SerialName("thread_id") val threadId: String,
    @SerialName("sender_user_id") val senderUserId: String,
    @SerialName("sender_name") val senderName: String? = null,
    @SerialName("sender_role") val senderRole: String,
    val body: String,
    @SerialName("attachment_storage_key") val attachmentStorageKey: String? = null,
    @SerialName("attachment_type") val attachmentType: String? = null,
    @SerialName("attachment_name") val attachmentName: String? = null,
    @SerialName("attachment_size_bytes") val attachmentSizeBytes: Long? = null,
    @SerialName("sent_at") val sentAt: String,
    @SerialName("delivered_at") val deliveredAt: String? = null,
    @SerialName("read_at") val readAt: String? = null,
    @SerialName("client_message_id") val clientMessageId: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class CreateThreadRequest(
    @SerialName("student_id") val studentId: String,
    val subject: String,
    @SerialName("staff_user_id") val staffUserId: String? = null,
    @SerialName("guardian_user_id") val guardianUserId: String? = null,
    @SerialName("first_message") val firstMessage: String? = null,
    @SerialName("linked_assignment_id") val linkedAssignmentId: String? = null,
    @SerialName("linked_summary_id") val linkedSummaryId: String? = null,
    @SerialName("attachment_storage_key") val attachmentStorageKey: String? = null,
    @SerialName("attachment_type") val attachmentType: String? = null,
    @SerialName("attachment_name") val attachmentName: String? = null,
    @SerialName("attachment_size_bytes") val attachmentSizeBytes: Long? = null,
    @SerialName("client_message_id") val clientMessageId: String? = null
)

@Serializable
data class SendMessageRequest(
    val body: String,
    @SerialName("attachment_storage_key") val attachmentStorageKey: String? = null,
    @SerialName("attachment_type") val attachmentType: String? = null,
    @SerialName("attachment_name") val attachmentName: String? = null,
    @SerialName("attachment_size_bytes") val attachmentSizeBytes: Long? = null,
    @SerialName("client_message_id") val clientMessageId: String? = null
)

@Serializable
data class DeliveredResponse(
    @SerialName("delivered_count") val deliveredCount: Int = 0
)

@Serializable
data class ReadResponse(
    @SerialName("read_count") val readCount: Int = 0
)

@Serializable
data class DailySummaryDto(
    val id: String,
    @SerialName("branch_id") val branchId: String? = null,
    @SerialName("room_id") val roomId: String,
    @SerialName("room_name") val roomName: String? = null,
    @SerialName("teacher_user_id") val teacherUserId: String,
    @SerialName("teacher_name") val teacherName: String? = null,
    @SerialName("summary_date") val summaryDate: String,
    val subject: String,
    val title: String,
    @SerialName("class_summary") val classSummary: String,
    @SerialName("homework_summary") val homeworkSummary: String? = null,
    @SerialName("is_published") val isPublished: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("student_notes") val studentNotes: List<StudentNoteDto> = emptyList(),
    @SerialName("student_private_note") val studentPrivateNote: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class StudentNoteDto(
    val id: String? = null,
    @SerialName("student_id") val studentId: String,
    @SerialName("student_name") val studentName: String? = null,
    @SerialName("private_note") val privateNote: String
)

@Serializable
data class UpsertDailySummaryRequest(
    @SerialName("room_id") val roomId: String,
    @SerialName("summary_date") val summaryDate: String,
    val subject: String,
    val title: String,
    @SerialName("class_summary") val classSummary: String,
    @SerialName("homework_summary") val homeworkSummary: String? = null,
    @SerialName("is_published") val isPublished: Boolean = false,
    @SerialName("student_notes") val studentNotes: List<StudentNoteDto> = emptyList()
)
