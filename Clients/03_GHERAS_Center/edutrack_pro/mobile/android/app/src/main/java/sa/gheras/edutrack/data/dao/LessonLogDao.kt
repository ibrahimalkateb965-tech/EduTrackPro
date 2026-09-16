package sa.gheras.edutrack.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import sa.gheras.edutrack.data.entity.LessonLogEntity
import java.time.Instant
import java.time.LocalDate

@Dao
interface LessonLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(log: LessonLogEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(logs: List<LessonLogEntity>): List<Long>

    @Update
    suspend fun update(log: LessonLogEntity)

    @Delete
    suspend fun delete(log: LessonLogEntity)

    @Query("DELETE FROM lesson_logs WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE lesson_logs SET deleted_at = :deletedAt, updated_at = :updatedAt WHERE id = :id")
    suspend fun markDeleted(id: String, deletedAt: Instant, updatedAt: Instant)

    @Query("SELECT * FROM lesson_logs WHERE id = :id")
    suspend fun getById(id: String): LessonLogEntity?

    @Query("SELECT * FROM lesson_logs WHERE schedule_id = :scheduleId AND date = :date LIMIT 1")
    suspend fun getByScheduleAndDate(scheduleId: String, date: LocalDate): LessonLogEntity?

    @Query("SELECT * FROM lesson_logs WHERE deleted_at IS NULL AND schedule_id = :scheduleId ORDER BY date DESC")
    fun observeBySchedule(scheduleId: String): Flow<List<LessonLogEntity>>

    @Query("SELECT * FROM lesson_logs WHERE deleted_at IS NULL AND schedule_id = :scheduleId AND date BETWEEN :from AND :to ORDER BY date")
    fun observeByScheduleBetween(scheduleId: String, from: LocalDate, to: LocalDate): Flow<List<LessonLogEntity>>

    @Query("SELECT * FROM lesson_logs WHERE deleted_at IS NULL AND date BETWEEN :from AND :to ORDER BY date")
    fun observeBetween(from: LocalDate, to: LocalDate): Flow<List<LessonLogEntity>>

    @Query("DELETE FROM lesson_logs")
    suspend fun clear()
}
