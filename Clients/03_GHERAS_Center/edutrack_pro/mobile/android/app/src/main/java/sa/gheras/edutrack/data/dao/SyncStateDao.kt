package sa.gheras.edutrack.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import sa.gheras.edutrack.data.entity.SyncStateEntity
import java.time.Instant

@Dao
interface SyncStateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: SyncStateEntity): Long

    @Query("SELECT * FROM sync_state WHERE resource = :resource")
    suspend fun get(resource: String): SyncStateEntity?

    @Query("SELECT * FROM sync_state ORDER BY resource ASC")
    suspend fun listAll(): List<SyncStateEntity>

    @Query("SELECT * FROM sync_state ORDER BY resource ASC")
    fun observeAll(): Flow<List<SyncStateEntity>>

    @Query("SELECT MIN(last_pull_at) FROM sync_state")
    fun observeOldestPullAt(): Flow<Instant?>

    @Query("DELETE FROM sync_state")
    suspend fun clear()
}
