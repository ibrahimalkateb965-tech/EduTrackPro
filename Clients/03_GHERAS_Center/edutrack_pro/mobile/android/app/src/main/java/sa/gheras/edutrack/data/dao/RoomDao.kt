package sa.gheras.edutrack.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import sa.gheras.edutrack.data.entity.RoomEntity
import java.time.Instant

@Dao
interface RoomDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(room: RoomEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rooms: List<RoomEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun upsertMinimal(rooms: List<RoomEntity>): List<Long>

    @Update
    suspend fun update(room: RoomEntity)

    @Delete
    suspend fun delete(room: RoomEntity)

    @Query("DELETE FROM rooms WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE rooms SET deleted_at = :deletedAt, updated_at = :updatedAt WHERE id = :id")
    suspend fun markDeleted(id: String, deletedAt: Instant, updatedAt: Instant)

    @Query("SELECT * FROM rooms WHERE id = :id")
    suspend fun getById(id: String): RoomEntity?

    @Query("SELECT * FROM rooms WHERE deleted_at IS NULL ORDER BY name")
    fun observeAll(): Flow<List<RoomEntity>>

    @Query("SELECT * FROM rooms WHERE deleted_at IS NULL AND group_name = :groupName ORDER BY name")
    fun observeByGroup(groupName: String): Flow<List<RoomEntity>>

    @Query("DELETE FROM rooms")
    suspend fun clear()

    @Transaction
    suspend fun replaceScope(rooms: List<RoomEntity>) {
        clear()
        upsertAll(rooms)
    }
}
