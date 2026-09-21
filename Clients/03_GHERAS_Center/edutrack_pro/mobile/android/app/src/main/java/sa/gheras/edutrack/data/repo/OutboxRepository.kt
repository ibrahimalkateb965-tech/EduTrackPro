package sa.gheras.edutrack.data.repo

import kotlinx.coroutines.flow.Flow
import sa.gheras.edutrack.data.dao.PendingWriteDao
import sa.gheras.edutrack.data.entity.PendingWriteEntity

data class FailedWrite(
    val id: String,
    val kind: String,
    val naturalKey: String,
    val errorMessage: String
)

class OutboxRepository(
    private val pendingWriteDao: PendingWriteDao
) {
    val pendingCount: Flow<Int> = pendingWriteDao.countPending()

    val failedWrites: Flow<List<PendingWriteEntity>> = pendingWriteDao.observeFailed()

    suspend fun dismissFailedWrite(id: String) {
        pendingWriteDao.deleteById(id)
    }

    suspend fun retryFailedWrite(id: String) {
        val item = pendingWriteDao.getById(id) ?: return
        pendingWriteDao.upsert(item.copy(attempts = 0, lastError = null))
    }
}
