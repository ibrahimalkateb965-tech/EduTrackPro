package sa.gheras.edutrack.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import sa.gheras.edutrack.data.entity.AssignmentStudentEntity
import java.time.Instant

@Dao
interface AssignmentStudentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(link: AssignmentStudentEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(links: List<AssignmentStudentEntity>): List<Long>

    @Update
    suspend fun update(link: AssignmentStudentEntity)

    @Delete
    suspend fun delete(link: AssignmentStudentEntity)

    @Query("DELETE FROM assignment_students WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM assignment_students WHERE assignment_id = :assignmentId")
    suspend fun deleteByAssignment(assignmentId: String)

    @Query("DELETE FROM assignment_students WHERE student_id = :studentId")
    suspend fun deleteByStudent(studentId: String)

    @Query("UPDATE assignment_students SET deleted_at = :deletedAt, updated_at = :updatedAt WHERE id = :id")
    suspend fun markDeleted(id: String, deletedAt: Instant, updatedAt: Instant)

    @Query("SELECT * FROM assignment_students WHERE id = :id")
    suspend fun getById(id: String): AssignmentStudentEntity?

    @Query("SELECT * FROM assignment_students WHERE assignment_id = :assignmentId AND deleted_at IS NULL")
    suspend fun getByAssignment(assignmentId: String): List<AssignmentStudentEntity>

    @Query("SELECT * FROM assignment_students WHERE assignment_id = :assignmentId AND deleted_at IS NULL")
    fun observeByAssignment(assignmentId: String): Flow<List<AssignmentStudentEntity>>

    @Query("SELECT * FROM assignment_students WHERE student_id = :studentId AND deleted_at IS NULL")
    fun observeByStudent(studentId: String): Flow<List<AssignmentStudentEntity>>

    @Query("SELECT * FROM assignment_students WHERE deleted_at IS NULL")
    fun observeAll(): Flow<List<AssignmentStudentEntity>>

    @Query("DELETE FROM assignment_students")
    suspend fun clear()

    @Transaction
    suspend fun replaceScope(links: List<AssignmentStudentEntity>) {
        clear()
        upsertAll(links)
    }
}
