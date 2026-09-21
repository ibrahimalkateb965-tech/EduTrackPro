package sa.gheras.edutrack.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import sa.gheras.edutrack.data.entity.SubmissionFileEntity
import java.time.Instant

@Dao
interface SubmissionFileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(file: SubmissionFileEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(files: List<SubmissionFileEntity>): List<Long>

    @Update
    suspend fun update(file: SubmissionFileEntity)

    @Delete
    suspend fun delete(file: SubmissionFileEntity)

    @Query("DELETE FROM submission_files WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM submission_files WHERE submission_id = :submissionId")
    suspend fun deleteBySubmission(submissionId: String)

    @Query("UPDATE submission_files SET deleted_at = :deletedAt, updated_at = :updatedAt WHERE id = :id")
    suspend fun markDeleted(id: String, deletedAt: Instant, updatedAt: Instant)

    @Query("SELECT * FROM submission_files WHERE id = :id")
    suspend fun getById(id: String): SubmissionFileEntity?

    @Query("SELECT * FROM submission_files WHERE submission_id = :submissionId AND deleted_at IS NULL")
    suspend fun getBySubmission(submissionId: String): List<SubmissionFileEntity>

    @Query("SELECT * FROM submission_files WHERE submission_id = :submissionId AND deleted_at IS NULL")
    fun observeBySubmission(submissionId: String): Flow<List<SubmissionFileEntity>>

    @Query("DELETE FROM submission_files")
    suspend fun clear()

    @Transaction
    suspend fun replaceScope(submissionId: String, items: List<SubmissionFileEntity>) {
        deleteBySubmission(submissionId)
        upsertAll(items)
    }

    @Transaction
    suspend fun replaceScopeAll(items: List<SubmissionFileEntity>) {
        clear()
        upsertAll(items)
    }
}
