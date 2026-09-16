package sa.gheras.edutrack.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import sa.gheras.edutrack.data.entity.GuardianEntity
import java.time.Instant

@Dao
interface GuardianDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(guardian: GuardianEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(guardians: List<GuardianEntity>): List<Long>

    @Update
    suspend fun update(guardian: GuardianEntity)

    @Delete
    suspend fun delete(guardian: GuardianEntity)

    @Query("DELETE FROM guardians WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE guardians SET deleted_at = :deletedAt, updated_at = :updatedAt WHERE id = :id")
    suspend fun markDeleted(id: String, deletedAt: Instant, updatedAt: Instant)

    @Query("SELECT * FROM guardians WHERE id = :id")
    suspend fun getById(id: String): GuardianEntity?

    @Query("SELECT * FROM guardians WHERE phone = :phone AND deleted_at IS NULL LIMIT 1")
    suspend fun getByPhone(phone: String): GuardianEntity?

    @Query("SELECT * FROM guardians WHERE deleted_at IS NULL ORDER BY name")
    fun observeAll(): Flow<List<GuardianEntity>>

    @Query("SELECT * FROM guardians WHERE deleted_at IS NULL AND name LIKE '%' || :query || '%' ORDER BY name")
    fun searchByName(query: String): Flow<List<GuardianEntity>>

    @Query("DELETE FROM guardians")
    suspend fun clear()
}
