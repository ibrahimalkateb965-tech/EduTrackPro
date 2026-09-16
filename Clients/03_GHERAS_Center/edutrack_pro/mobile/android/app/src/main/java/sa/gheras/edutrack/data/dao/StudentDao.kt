package sa.gheras.edutrack.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import sa.gheras.edutrack.data.entity.StudentEntity
import java.time.Instant

@Dao
interface StudentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(student: StudentEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(students: List<StudentEntity>): List<Long>

    @Update
    suspend fun update(student: StudentEntity)

    @Delete
    suspend fun delete(student: StudentEntity)

    @Query("DELETE FROM students WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE students SET deleted_at = :deletedAt, updated_at = :updatedAt WHERE id = :id")
    suspend fun markDeleted(id: String, deletedAt: Instant, updatedAt: Instant)

    @Query("SELECT * FROM students WHERE id = :id")
    suspend fun getById(id: String): StudentEntity?

    @Query("SELECT * FROM students WHERE national_id = :nationalId AND deleted_at IS NULL LIMIT 1")
    suspend fun getByNationalId(nationalId: String): StudentEntity?

    @Query("SELECT * FROM students WHERE deleted_at IS NULL ORDER BY name")
    fun observeAll(): Flow<List<StudentEntity>>

    @Query("SELECT * FROM students WHERE deleted_at IS NULL AND room_id = :roomId ORDER BY name")
    fun observeByRoom(roomId: String): Flow<List<StudentEntity>>

    @Query("SELECT * FROM students WHERE deleted_at IS NULL AND status = :status ORDER BY name")
    fun observeByStatus(status: String): Flow<List<StudentEntity>>

    @Query("SELECT * FROM students WHERE deleted_at IS NULL AND group_name = :groupName ORDER BY name")
    fun observeByGroup(groupName: String): Flow<List<StudentEntity>>

    @Query("SELECT * FROM students WHERE deleted_at IS NULL AND name LIKE '%' || :query || '%' ORDER BY name")
    fun searchByName(query: String): Flow<List<StudentEntity>>

    @Query("SELECT COUNT(*) FROM students WHERE deleted_at IS NULL AND status = :status")
    fun countByStatus(status: String): Flow<Int>

    @Query("DELETE FROM students")
    suspend fun clear()
}
