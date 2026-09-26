package sa.gheras.edutrack.data.repo.mappers

import sa.gheras.edutrack.data.entity.InstallmentEntity
import sa.gheras.edutrack.data.entity.ReceiptEntity
import sa.gheras.edutrack.data.remote.dto.InstallmentDto
import sa.gheras.edutrack.data.remote.dto.ReceiptDto
import java.time.Instant
import java.time.LocalDate

object FeesMappers {

    fun installmentToEntity(dto: InstallmentDto, defaultBranchId: String? = null): InstallmentEntity {
        val now = Instant.now()
        val isPaid = dto.status.equals("PAID", ignoreCase = true)
        val paidAmt = dto.paidAmount ?: if (isPaid) (dto.amount ?: 0.0) else 0.0
        return InstallmentEntity(
            id = dto.id,
            branchId = dto.branchId ?: defaultBranchId,
            studentId = dto.studentId ?: "",
            feePlanId = dto.feePlanId ?: "default_plan",
            seqNo = dto.seqNo ?: 1,
            dueDate = DateParsers.parseLocalDate(dto.dueDate) ?: LocalDate.now(),
            amount = dto.amount ?: 0.0,
            paidAmount = paidAmt,
            status = dto.status,
            createdAt = DateParsers.parseInstant(dto.createdAt),
            updatedAt = DateParsers.parseInstant(dto.updatedAt),
            deletedAt = null
        )
    }

    fun receiptToEntity(dto: ReceiptDto, defaultBranchId: String? = null): ReceiptEntity {
        val now = Instant.now()
        val num = dto.receiptNo ?: dto.receiptNumber?.filter { it.isDigit() }?.toIntOrNull() ?: 1
        return ReceiptEntity(
            id = dto.id,
            branchId = dto.branchId ?: defaultBranchId,
            paymentId = dto.paymentId ?: dto.id,
            studentId = dto.studentId ?: "",
            installmentId = dto.installmentId,
            receiptNo = num,
            amount = dto.amount ?: 0.0,
            method = dto.method ?: "CASH",
            paidOn = DateParsers.parseLocalDate(dto.paidOn) ?: LocalDate.now(),
            issuedOn = DateParsers.parseInstant(dto.issuedOn),
            createdAt = DateParsers.parseInstant(dto.createdAt),
            updatedAt = DateParsers.parseInstant(dto.updatedAt),
            deletedAt = null
        )
    }
}
