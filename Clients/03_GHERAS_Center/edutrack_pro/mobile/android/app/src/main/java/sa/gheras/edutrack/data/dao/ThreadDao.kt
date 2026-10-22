package sa.gheras.edutrack.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import sa.gheras.edutrack.data.entity.ThreadEntity
import sa.gheras.edutrack.data.entity.ThreadMessageEntity

@Dao
interface ThreadDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertThread(thread: ThreadEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertThreads(threads: List<ThreadEntity>): List<Long>

    @Query("SELECT * FROM threads WHERE id = :id")
    suspend fun getThreadById(id: String): ThreadEntity?

    @Query("SELECT * FROM threads ORDER BY last_message_at DESC")
    fun observeThreads(): Flow<List<ThreadEntity>>

    @Query("SELECT * FROM threads WHERE student_id = :studentId ORDER BY last_message_at DESC")
    fun observeThreadsByStudent(studentId: String): Flow<List<ThreadEntity>>

    @Query("DELETE FROM threads WHERE id = :id")
    suspend fun deleteThreadById(id: String)

    @Query("DELETE FROM threads")
    suspend fun clearThreads()

    // --- Message operations ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(message: ThreadMessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessages(messages: List<ThreadMessageEntity>): List<Long>

    @Query("SELECT * FROM thread_messages WHERE id = :id")
    suspend fun getMessageById(id: String): ThreadMessageEntity?

    @Query("SELECT * FROM thread_messages WHERE thread_id = :threadId ORDER BY sent_at ASC")
    fun observeMessagesByThread(threadId: String): Flow<List<ThreadMessageEntity>>

    @Query("SELECT * FROM thread_messages WHERE thread_id = :threadId ORDER BY sent_at ASC")
    suspend fun getMessagesByThread(threadId: String): List<ThreadMessageEntity>

    @Query("DELETE FROM thread_messages WHERE id = :id")
    suspend fun deleteMessageById(id: String)

    @Query("DELETE FROM thread_messages WHERE thread_id = :threadId")
    suspend fun clearMessagesByThread(threadId: String)

    @Query("DELETE FROM thread_messages")
    suspend fun clearMessages()

    @Transaction
    suspend fun replaceThreadMessages(threadId: String, items: List<ThreadMessageEntity>) {
        clearMessagesByThread(threadId)
        upsertMessages(items)
    }
}
