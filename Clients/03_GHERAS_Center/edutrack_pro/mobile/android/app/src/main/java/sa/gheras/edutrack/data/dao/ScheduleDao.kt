package sa.gheras.edutrack.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import sa.gheras.edutrack.data.entity.ScheduleEntity
import java.time.Instant

@Dao
interface ScheduleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(schedule: ScheduleEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(schedules: List<ScheduleEntity>): List<Long>

    @Update
    suspend fun update(schedule: ScheduleEntity)

    @Delete
    suspend fun delete(schedule: ScheduleEntity)

    @Query("DELETE FROM schedules WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE schedules SET deleted_at = :deletedAt, updated_at = :updatedAt WHERE id = :id")
    suspend fun markDeleted(id: String, deletedAt: Instant, updatedAt: Instant)

    @Query("SELECT * FROM schedules WHERE id = :id")
    suspend fun getById(id: String): ScheduleEntity?

    @Query("SELECT * FROM schedules WHERE deleted_at IS NULL ORDER BY day, start_time")
    fun observeAll(): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedules WHERE deleted_at IS NULL AND room_id = :roomId ORDER BY day, start_time")
    fun observeByRoom(roomId: String): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedules WHERE deleted_at IS NULL AND day = :day ORDER BY start_time")
    fun observeByDay(day: String): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedules WHERE deleted_at IS NULL AND teacher_user_id = :teacherUserId ORDER BY day, start_time")
    fun observeByTeacher(teacherUserId: String): Flow<List<ScheduleEntity>>

    @Query("DELETE FROM schedules")
    suspend fun clear()

    @Transaction
    suspend fun replaceScope(schedules: List<ScheduleEntity>) {
        clear()
        upsertAll(schedules)
    }
}
