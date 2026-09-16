package sa.gheras.edutrack.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import sa.gheras.edutrack.data.entity.AssignmentEntity
import java.time.Instant
import java.time.LocalDate

@Dao
interface AssignmentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(assignment: AssignmentEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(assignments: List<AssignmentEntity>): List<Long>

    @Update
    suspend fun update(assignment: AssignmentEntity)

    @Delete
    suspend fun delete(assignment: AssignmentEntity)

    @Query("DELETE FROM assignments WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE assignments SET deleted_at = :deletedAt, updated_at = :updatedAt WHERE id = :id")
    suspend fun markDeleted(id: String, deletedAt: Instant, updatedAt: Instant)

    @Query("SELECT * FROM assignments WHERE id = :id")
    suspend fun getById(id: String): AssignmentEntity?

    @Query("SELECT * FROM assignments WHERE deleted_at IS NULL ORDER BY due_date DESC")
    fun observeAll(): Flow<List<AssignmentEntity>>

    @Query(
        """
        SELECT assignments.* FROM assignments
        INNER JOIN assignment_students ON assignment_students.assignment_id = assignments.id
        WHERE assignment_students.student_id = :studentId
          AND assignments.deleted_at IS NULL
          AND assignment_students.deleted_at IS NULL
        ORDER BY assignments.due_date DESC
        """
    )
    fun observeByStudent(studentId: String): Flow<List<AssignmentEntity>>

    @Query("SELECT * FROM assignments WHERE deleted_at IS NULL AND due_date BETWEEN :from AND :to ORDER BY due_date")
    fun observeDueBetween(from: LocalDate, to: LocalDate): Flow<List<AssignmentEntity>>

    @Query("SELECT * FROM assignments WHERE deleted_at IS NULL AND teacher_user_id = :teacherUserId ORDER BY due_date DESC")
    fun observeByTeacher(teacherUserId: String): Flow<List<AssignmentEntity>>

    @Query("DELETE FROM assignments")
    suspend fun clear()
}
