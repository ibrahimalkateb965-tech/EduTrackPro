package sa.gheras.edutrack.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class InstallmentDto(
    val id: String,
    @SerialName("branch_id") val branchId: String? = null,
    @SerialName("fee_plan_id") val feePlanId: String? = null,
    @SerialName("student_id") val studentId: String? = null,
    @SerialName("seq_no") val seqNo: Int? = null,
    val amount: Double? = null,
    @SerialName("paid_amount") val paidAmount: Double? = null,
    @SerialName("due_date") val dueDate: String,
    val status: String,
    @SerialName("student_name") val studentName: String? = null,
    @SerialName("plan_total") val planTotal: Double? = null,
    @SerialName("plan_count") val planCount: Int? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)
