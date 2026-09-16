package sa.gheras.edutrack.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import sa.gheras.edutrack.data.entity.EvaluationEntity
import java.time.Instant
import java.time.LocalDate

@Dao
interface EvaluationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(evaluation: EvaluationEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(evaluations: List<EvaluationEntity>): List<Long>

    @Update
    suspend fun update(evaluation: EvaluationEntity)

    @Delete
    suspend fun delete(evaluation: EvaluationEntity)

    @Query("DELETE FROM evaluations WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE evaluations SET deleted_at = :deletedAt, updated_at = :updatedAt WHERE id = :id")
    suspend fun markDeleted(id: String, deletedAt: Instant, updatedAt: Instant)

    @Query("SELECT * FROM evaluations WHERE id = :id")
    suspend fun getById(id: String): EvaluationEntity?

    @Query("SELECT * FROM evaluations WHERE deleted_at IS NULL AND student_id = :studentId ORDER BY date DESC")
    fun observeByStudent(studentId: String): Flow<List<EvaluationEntity>>

    @Query("SELECT * FROM evaluations WHERE deleted_at IS NULL AND student_id = :studentId AND subject = :subject ORDER BY date DESC")
    fun observeByStudentAndSubject(studentId: String, subject: String): Flow<List<EvaluationEntity>>

    @Query("SELECT * FROM evaluations WHERE deleted_at IS NULL AND student_id = :studentId AND eval_type = :evalType ORDER BY date DESC")
    fun observeByStudentAndType(studentId: String, evalType: String): Flow<List<EvaluationEntity>>

    @Query("SELECT * FROM evaluations WHERE deleted_at IS NULL AND date BETWEEN :from AND :to ORDER BY date")
    fun observeBetween(from: LocalDate, to: LocalDate): Flow<List<EvaluationEntity>>

    @Query("DELETE FROM evaluations")
    suspend fun clear()
}
