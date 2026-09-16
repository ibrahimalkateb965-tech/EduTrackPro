package sa.gheras.edutrack.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import sa.gheras.edutrack.data.entity.SkillProgressEntity
import java.time.Instant

@Dao
interface SkillProgressDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(progress: SkillProgressEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(progress: List<SkillProgressEntity>): List<Long>

    @Update
    suspend fun update(progress: SkillProgressEntity)

    @Delete
    suspend fun delete(progress: SkillProgressEntity)

    @Query("DELETE FROM skill_progress WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE skill_progress SET deleted_at = :deletedAt, updated_at = :updatedAt WHERE id = :id")
    suspend fun markDeleted(id: String, deletedAt: Instant, updatedAt: Instant)

    @Query("SELECT * FROM skill_progress WHERE id = :id")
    suspend fun getById(id: String): SkillProgressEntity?

    @Query("SELECT * FROM skill_progress WHERE deleted_at IS NULL AND student_id = :studentId ORDER BY date DESC")
    fun observeByStudent(studentId: String): Flow<List<SkillProgressEntity>>

    @Query("SELECT * FROM skill_progress WHERE deleted_at IS NULL AND student_id = :studentId AND subject = :subject ORDER BY date DESC")
    fun observeByStudentAndSubject(studentId: String, subject: String): Flow<List<SkillProgressEntity>>

    @Query(
        """
        SELECT * FROM skill_progress AS sp
        WHERE sp.deleted_at IS NULL
          AND sp.student_id = :studentId
          AND sp.date = (
              SELECT MAX(x.date) FROM skill_progress AS x
              WHERE x.student_id = :studentId
                AND x.skill = sp.skill
                AND x.deleted_at IS NULL
          )
        ORDER BY sp.subject, sp.skill
        """
    )
    fun observeLatestByStudent(studentId: String): Flow<List<SkillProgressEntity>>

    @Query("DELETE FROM skill_progress")
    suspend fun clear()
}
