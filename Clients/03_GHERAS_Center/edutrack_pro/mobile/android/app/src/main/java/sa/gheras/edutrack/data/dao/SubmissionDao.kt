package sa.gheras.edutrack.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import sa.gheras.edutrack.data.entity.SubmissionEntity
import java.time.Instant

@Dao
interface SubmissionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(submission: SubmissionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(submissions: List<SubmissionEntity>): List<Long>

    @Update
    suspend fun update(submission: SubmissionEntity)

    @Delete
    suspend fun delete(submission: SubmissionEntity)

    @Query("DELETE FROM submissions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE submissions SET deleted_at = :deletedAt, updated_at = :updatedAt WHERE id = :id")
    suspend fun markDeleted(id: String, deletedAt: Instant, updatedAt: Instant)

    @Query("SELECT * FROM submissions WHERE id = :id")
    suspend fun getById(id: String): SubmissionEntity?

    @Query("SELECT * FROM submissions WHERE assignment_id = :assignmentId AND student_id = :studentId AND deleted_at IS NULL LIMIT 1")
    suspend fun getByAssignmentAndStudent(assignmentId: String, studentId: String): SubmissionEntity?

    @Query("SELECT * FROM submissions WHERE deleted_at IS NULL AND assignment_id = :assignmentId ORDER BY submitted_at DESC")
    fun observeByAssignment(assignmentId: String): Flow<List<SubmissionEntity>>

    @Query("SELECT * FROM submissions WHERE deleted_at IS NULL AND student_id = :studentId ORDER BY submitted_at DESC")
    fun observeByStudent(studentId: String): Flow<List<SubmissionEntity>>

    @Query("SELECT * FROM submissions WHERE deleted_at IS NULL AND status = :status ORDER BY submitted_at DESC")
    fun observeByStatus(status: String): Flow<List<SubmissionEntity>>

    @Query("SELECT * FROM submissions WHERE deleted_at IS NULL ORDER BY submitted_at DESC")
    fun observeAll(): Flow<List<SubmissionEntity>>

    @Query("DELETE FROM submissions")
    suspend fun clear()

    @Transaction
    suspend fun replaceScope(submissions: List<SubmissionEntity>) {
        clear()
        upsertAll(submissions)
    }
}
