package sa.gheras.edutrack.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ReceiptDto(
    val id: String,
    @SerialName("branch_id") val branchId: String? = null,
    @SerialName("payment_id") val paymentId: String? = null,
    @SerialName("student_id") val studentId: String? = null,
    @SerialName("installment_id") val installmentId: String? = null,
    val amount: Double? = null,
    val method: String? = null,
    @SerialName("paid_on") val paidOn: String? = null,
    @SerialName("issued_on") val issuedOn: String? = null,
    @SerialName("receipt_no") val receiptNo: Int? = null,
    @SerialName("receipt_number") val receiptNumber: String? = null,
    @SerialName("student_name") val studentName: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)
