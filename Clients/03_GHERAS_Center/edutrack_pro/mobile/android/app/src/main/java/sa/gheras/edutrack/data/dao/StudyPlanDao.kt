package sa.gheras.edutrack.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import sa.gheras.edutrack.data.entity.StudyPlanEntity
import java.time.Instant
import java.time.LocalDate

@Dao
interface StudyPlanDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(plan: StudyPlanEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(plans: List<StudyPlanEntity>): List<Long>

    @Update
    suspend fun update(plan: StudyPlanEntity)

    @Delete
    suspend fun delete(plan: StudyPlanEntity)

    @Query("DELETE FROM study_plans WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE study_plans SET deleted_at = :deletedAt, updated_at = :updatedAt WHERE id = :id")
    suspend fun markDeleted(id: String, deletedAt: Instant, updatedAt: Instant)

    @Query("SELECT * FROM study_plans WHERE id = :id")
    suspend fun getById(id: String): StudyPlanEntity?

    @Query("SELECT * FROM study_plans WHERE deleted_at IS NULL AND student_id = :studentId ORDER BY start_date DESC")
    fun observeByStudent(studentId: String): Flow<List<StudyPlanEntity>>

    @Query("SELECT * FROM study_plans WHERE deleted_at IS NULL AND student_id = :studentId AND (end_date IS NULL OR end_date >= :today) ORDER BY start_date DESC")
    fun observeActiveByStudent(studentId: String, today: LocalDate): Flow<List<StudyPlanEntity>>

    @Query("DELETE FROM study_plans")
    suspend fun clear()
}
