package sa.gheras.edutrack.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import sa.gheras.edutrack.data.entity.NotificationEntity
import java.time.Instant

@Dao
interface NotificationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(notification: NotificationEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(notifications: List<NotificationEntity>): List<Long>

    @Update
    suspend fun update(notification: NotificationEntity)

    @Delete
    suspend fun delete(notification: NotificationEntity)

    @Query("DELETE FROM notifications WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM notifications WHERE id = :id")
    suspend fun getById(id: String): NotificationEntity?

    @Query("SELECT * FROM notifications WHERE deleted_at IS NULL AND user_id = :userId ORDER BY sent_at DESC")
    fun observeByUser(userId: String): Flow<List<NotificationEntity>>

    @Query("SELECT * FROM notifications WHERE deleted_at IS NULL AND user_id = :userId AND read_at IS NULL ORDER BY sent_at DESC")
    fun observeUnreadByUser(userId: String): Flow<List<NotificationEntity>>

    @Query("SELECT * FROM notifications WHERE deleted_at IS NULL AND user_id = :userId AND read_at IS NULL")
    suspend fun getUnreadByUser(userId: String): List<NotificationEntity>

    @Query("SELECT COUNT(*) FROM notifications WHERE deleted_at IS NULL AND user_id = :userId AND read_at IS NULL")
    fun countUnreadByUser(userId: String): Flow<Int>

    @Query("UPDATE notifications SET read_at = :readAt, updated_at = :updatedAt WHERE id = :id AND read_at IS NULL")
    suspend fun markRead(id: String, readAt: Instant, updatedAt: Instant)

    @Query("UPDATE notifications SET read_at = :readAt, updated_at = :updatedAt WHERE user_id = :userId AND read_at IS NULL")
    suspend fun markAllReadByUser(userId: String, readAt: Instant, updatedAt: Instant)

    @Query("DELETE FROM notifications WHERE user_id = :userId")
    suspend fun clearByUser(userId: String)

    @Query("DELETE FROM notifications")
    suspend fun clear()

    @Query("UPDATE notifications SET deleted_at = :now, updated_at = :now WHERE id IN (:ids)")
    suspend fun softDelete(ids: List<String>, now: Instant)

    @Query("UPDATE notifications SET deleted_at = NULL WHERE id IN (:ids)")
    suspend fun restore(ids: List<String>)

    @Query("SELECT id FROM notifications WHERE user_id = :userId AND deleted_at IS NULL AND read_at IS NOT NULL")
    suspend fun getReadIds(userId: String): List<String>

    @Transaction
    suspend fun replaceScope(userId: String, items: List<NotificationEntity>) {
        clearByUser(userId)
        upsertAll(items)
    }

    @Transaction
    suspend fun replaceScopeAll(items: List<NotificationEntity>) {
        clear()
        upsertAll(items)
    }
}
