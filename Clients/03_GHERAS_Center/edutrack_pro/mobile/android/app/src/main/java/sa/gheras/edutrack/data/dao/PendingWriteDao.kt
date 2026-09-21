package sa.gheras.edutrack.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import sa.gheras.edutrack.data.entity.PendingWriteEntity

@Dao
interface PendingWriteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(write: PendingWriteEntity): Long

    @Query("SELECT * FROM pending_writes WHERE id = :id")
    suspend fun getById(id: String): PendingWriteEntity?

    @Query("SELECT * FROM pending_writes WHERE natural_key = :naturalKey")
    suspend fun getByNaturalKey(naturalKey: String): PendingWriteEntity?

    @Query("SELECT * FROM pending_writes WHERE attempts >= 0 ORDER BY created_at ASC")
    suspend fun listPending(): List<PendingWriteEntity>

    @Query("SELECT * FROM pending_writes WHERE attempts >= 0 ORDER BY created_at ASC")
    fun observePending(): Flow<List<PendingWriteEntity>>

    @Query("SELECT COUNT(*) FROM pending_writes WHERE attempts >= 0")
    fun countPending(): Flow<Int>

    @Query("SELECT * FROM pending_writes WHERE attempts = -1 ORDER BY created_at ASC")
    fun observeFailed(): Flow<List<PendingWriteEntity>>

    @Query("UPDATE pending_writes SET attempts = attempts + 1, last_error = :error WHERE id = :id AND attempts >= 0")
    suspend fun recordAttempt(id: String, error: String?)

    @Query("UPDATE pending_writes SET attempts = -1, last_error = :error WHERE id = :id")
    suspend fun markFailed(id: String, error: String)

    @Query("DELETE FROM pending_writes WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM pending_writes WHERE natural_key = :naturalKey")
    suspend fun deleteByNaturalKey(naturalKey: String)
}
