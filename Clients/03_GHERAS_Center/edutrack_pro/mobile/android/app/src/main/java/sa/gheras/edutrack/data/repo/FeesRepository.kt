package sa.gheras.edutrack.data.repo

import kotlinx.coroutines.flow.Flow
import sa.gheras.edutrack.data.dao.InstallmentDao
import sa.gheras.edutrack.data.dao.ReceiptDao
import sa.gheras.edutrack.data.entity.InstallmentEntity
import sa.gheras.edutrack.data.entity.ReceiptEntity

class FeesRepository(
    private val installmentDao: InstallmentDao,
    private val receiptDao: ReceiptDao
) {
    fun observeInstallments(studentId: String): Flow<List<InstallmentEntity>> =
        installmentDao.observeByStudent(studentId)

    fun observeReceipts(studentId: String): Flow<List<ReceiptEntity>> =
        receiptDao.observeByStudent(studentId)
}
