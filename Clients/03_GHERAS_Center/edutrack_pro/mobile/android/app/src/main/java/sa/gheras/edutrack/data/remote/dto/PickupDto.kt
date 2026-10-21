package sa.gheras.edutrack.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PickupDelegateDto(
    val id: String,
    @SerialName("student_id") val studentId: String,
    val name: String,
    val phone: String,
    val relation: String,
    @SerialName("is_approved") val isApproved: Boolean = true,
    @SerialName("client_request_id") val clientRequestId: String? = null,
    @SerialName("national_id_masked") val nationalIdMasked: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class PickupDelegatesResponse(
    val delegates: List<PickupDelegateDto> = emptyList()
)

@Serializable
data class CreateDelegateRequest(
    @SerialName("student_id") val studentId: String,
    val name: String,
    @SerialName("national_id") val nationalId: String,
    val phone: String,
    val relation: String,
    @SerialName("client_request_id") val clientRequestId: String? = null
)

@Serializable
data class CreatePickupRequest(
    @SerialName("student_id") val studentId: String,
    @SerialName("delegate_id") val delegateId: String? = null,
    @SerialName("is_early_leave") val isEarlyLeave: Boolean = false,
    @SerialName("client_request_id") val clientRequestId: String? = null
)

@Serializable
data class PickupRequestDto(
    val id: String,
    @SerialName("branch_id") val branchId: String? = null,
    @SerialName("student_id") val studentId: String,
    @SerialName("student_name") val studentName: String? = null,
    @SerialName("room_name") val roomName: String? = null,
    @SerialName("requested_by_user_id") val requestedByUserId: String? = null,
    @SerialName("delegate_id") val delegateId: String? = null,
    @SerialName("delegate_name") val delegateName: String? = null,
    val status: String,
    @SerialName("pickup_date") val pickupDate: String? = null,
    @SerialName("pickup_time") val pickupTime: String? = null,
    @SerialName("room_id") val roomId: String? = null,
    @SerialName("delegate_phone") val delegatePhone: String? = null,
    @SerialName("delegate_relation") val delegateRelation: String? = null,
    @SerialName("delegate_national_id_masked") val delegateNationalIdMasked: String? = null,
    @SerialName("guardian_national_id_masked") val guardianNationalIdMasked: String? = null,
    @SerialName("is_early_leave") val isEarlyLeave: Boolean = false,
    @SerialName("early_leave_approved") val earlyLeaveApproved: Boolean = false,
    @SerialName("client_request_id") val clientRequestId: String? = null,
    @SerialName("created_at") val createdAt: String? = null
)

@Serializable
data class ActivePickupRequestsResponse(
    val requests: List<PickupRequestDto> = emptyList()
)

@Serializable
data class HandoverRequest(
    @SerialName("verified_receiver_id_no") val verifiedReceiverIdNo: String,
    @SerialName("verified_receiver_name") val verifiedReceiverName: String,
    @SerialName("verified_receiver_phone") val verifiedReceiverPhone: String,
    @SerialName("verified_receiver_relation") val verifiedReceiverRelation: String
)

@Serializable
data class CancelPickupRequest(
    @SerialName("cancel_reason") val cancelReason: String
)

@Serializable
data class FailPickupRequest(
    @SerialName("failure_reason") val failureReason: String
)

object PickupStatus {
    const val NOTIFIED = "notified"
    const val IN_PREP = "in_prep"
    const val READY = "ready"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"

    val ACTIVE_STATUSES = listOf(NOTIFIED, IN_PREP, READY)
    val TERMINAL_STATUSES = listOf(COMPLETED, FAILED, CANCELLED)
}
