package sa.gheras.edutrack.data.repo.mappers

import org.junit.Assert.assertEquals
import org.junit.Test
import sa.gheras.edutrack.data.remote.dto.InstallmentDto
import sa.gheras.edutrack.data.remote.dto.ReceiptDto

class FeesMapperTest {

    @Test
    fun testInstallmentMapping() {
        val dto = InstallmentDto(
            id = "inst_1",
            feePlanId = "plan_1",
            studentId = "s_1",
            amount = 500.0,
            dueDate = "2026-10-01",
            status = "PENDING"
        )
        val entity = FeesMappers.installmentToEntity(dto, "branch_1")
        assertEquals("inst_1", entity.id)
        assertEquals("s_1", entity.studentId)
        assertEquals(500.0, entity.amount, 0.01)
        assertEquals("PENDING", entity.status)
        assertEquals(0.0, entity.paidAmount, 0.01)
    }

    @Test
    fun testReceiptMapping() {
        val dto = ReceiptDto(
            id = "rc_1",
            paymentId = "pay_1",
            studentId = "s_1",
            installmentId = "inst_1",
            amount = 500.0,
            method = "CARD",
            paidOn = "2026-09-21",
            issuedOn = "2026-09-21T08:00:00Z",
            receiptNumber = "REC-1002"
        )
        val entity = FeesMappers.receiptToEntity(dto, "branch_1")
        assertEquals("rc_1", entity.id)
        assertEquals("s_1", entity.studentId)
        assertEquals("inst_1", entity.installmentId)
        assertEquals(500.0, entity.amount, 0.01)
        assertEquals("CARD", entity.method)
        assertEquals(1002, entity.receiptNo)
    }
}
