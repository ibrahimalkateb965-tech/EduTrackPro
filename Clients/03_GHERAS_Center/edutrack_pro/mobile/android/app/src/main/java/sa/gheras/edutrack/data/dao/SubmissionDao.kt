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

    @Query("SELECT * FROM submissions WHERE assignment_id = :assignmentId AND student_id = :studentId AND deleted_at IS NULL ORDER BY attempt_number DESC LIMIT 1")
    suspend fun getByAssignmentAndStudent(assignmentId: String, studentId: String): SubmissionEntity?

    @Query("SELECT * FROM submissions WHERE assignment_id = :assignmentId AND student_id = :studentId AND deleted_at IS NULL ORDER BY attempt_number DESC LIMIT 1")
    suspend fun getLatestByAssignmentAndStudent(assignmentId: String, studentId: String): SubmissionEntity?

    @Query("SELECT * FROM submissions WHERE assignment_id = :assignmentId AND student_id = :studentId AND deleted_at IS NULL ORDER BY attempt_number DESC LIMIT 1")
    fun observeLatestByAssignmentAndStudent(assignmentId: String, studentId: String): Flow<SubmissionEntity?>

    @Query("SELECT * FROM submissions WHERE assignment_id = :assignmentId AND student_id = :studentId AND attempt_number = :attemptNumber AND deleted_at IS NULL LIMIT 1")
    suspend fun getByAttempt(assignmentId: String, studentId: String, attemptNumber: Int): SubmissionEntity?

    @Query("SELECT * FROM submissions WHERE assignment_id = :assignmentId AND student_id = :studentId AND deleted_at IS NULL ORDER BY attempt_number ASC")
    fun observeAttempts(assignmentId: String, studentId: String): Flow<List<SubmissionEntity>>

    @Query("SELECT * FROM submissions WHERE assignment_id = :assignmentId AND student_id = :studentId AND deleted_at IS NULL ORDER BY attempt_number ASC")
    suspend fun getAttempts(assignmentId: String, studentId: String): List<SubmissionEntity>

    @Query("SELECT * FROM submissions WHERE deleted_at IS NULL AND assignment_id = :assignmentId ORDER BY attempt_number DESC, submitted_at DESC")
    fun observeByAssignment(assignmentId: String): Flow<List<SubmissionEntity>>

    @Query("SELECT * FROM submissions WHERE deleted_at IS NULL AND student_id = :studentId ORDER BY attempt_number DESC, submitted_at DESC")
    fun observeByStudent(studentId: String): Flow<List<SubmissionEntity>>

    @Query(
        """
        SELECT s1.* FROM submissions s1
        WHERE s1.student_id = :studentId AND s1.deleted_at IS NULL
          AND s1.attempt_number = (
              SELECT MAX(s2.attempt_number) FROM submissions s2
              WHERE s2.assignment_id = s1.assignment_id AND s2.student_id = s1.student_id AND s2.deleted_at IS NULL
          )
        ORDER BY s1.submitted_at DESC
        """
    )
    fun observeLatestByStudent(studentId: String): Flow<List<SubmissionEntity>>

    @Query(
        """
        SELECT s1.* FROM submissions s1
        WHERE s1.assignment_id = :assignmentId AND s1.deleted_at IS NULL
          AND s1.attempt_number = (
              SELECT MAX(s2.attempt_number) FROM submissions s2
              WHERE s2.assignment_id = s1.assignment_id AND s2.student_id = s1.student_id AND s2.deleted_at IS NULL
          )
        ORDER BY s1.submitted_at DESC
        """
    )
    fun observeLatestByAssignment(assignmentId: String): Flow<List<SubmissionEntity>>

    @Query(
        """
        SELECT s1.* FROM submissions s1
        WHERE s1.assignment_id = :assignmentId AND s1.deleted_at IS NULL
          AND s1.attempt_number = (
              SELECT MAX(s2.attempt_number) FROM submissions s2
              WHERE s2.assignment_id = s1.assignment_id AND s2.student_id = s1.student_id AND s2.deleted_at IS NULL
          )
        """
    )
    suspend fun getLatestByAssignment(assignmentId: String): List<SubmissionEntity>

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
