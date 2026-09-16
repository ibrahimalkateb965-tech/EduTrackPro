package sa.gheras.edutrack.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import sa.gheras.edutrack.data.entity.StudentAttendanceEntity
import java.time.Instant
import java.time.LocalDate

@Dao
interface StudentAttendanceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(attendance: StudentAttendanceEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(attendance: List<StudentAttendanceEntity>): List<Long>

    @Update
    suspend fun update(attendance: StudentAttendanceEntity)

    @Delete
    suspend fun delete(attendance: StudentAttendanceEntity)

    @Query("DELETE FROM student_attendance WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE student_attendance SET deleted_at = :deletedAt, updated_at = :updatedAt WHERE id = :id")
    suspend fun markDeleted(id: String, deletedAt: Instant, updatedAt: Instant)

    @Query("SELECT * FROM student_attendance WHERE id = :id")
    suspend fun getById(id: String): StudentAttendanceEntity?

    @Query("SELECT * FROM student_attendance WHERE student_id = :studentId AND date = :date LIMIT 1")
    suspend fun getByStudentAndDate(studentId: String, date: LocalDate): StudentAttendanceEntity?

    @Query("SELECT * FROM student_attendance WHERE deleted_at IS NULL AND date = :date ORDER BY student_id")
    fun observeByDate(date: LocalDate): Flow<List<StudentAttendanceEntity>>

    @Query("SELECT * FROM student_attendance WHERE deleted_at IS NULL AND student_id = :studentId ORDER BY date DESC")
    fun observeByStudent(studentId: String): Flow<List<StudentAttendanceEntity>>

    @Query("SELECT * FROM student_attendance WHERE deleted_at IS NULL AND student_id = :studentId AND date BETWEEN :from AND :to ORDER BY date")
    fun observeByStudentBetween(studentId: String, from: LocalDate, to: LocalDate): Flow<List<StudentAttendanceEntity>>

    @Query("SELECT * FROM student_attendance WHERE deleted_at IS NULL AND date BETWEEN :from AND :to ORDER BY date, student_id")
    fun observeBetween(from: LocalDate, to: LocalDate): Flow<List<StudentAttendanceEntity>>

    @Query("SELECT COUNT(*) FROM student_attendance WHERE deleted_at IS NULL AND date BETWEEN :from AND :to AND status = :status")
    fun countByStatusBetween(status: String, from: LocalDate, to: LocalDate): Flow<Int>

    @Query("DELETE FROM student_attendance")
    suspend fun clear()
}
