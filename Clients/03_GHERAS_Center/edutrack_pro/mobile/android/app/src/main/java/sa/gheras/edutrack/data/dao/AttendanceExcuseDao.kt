package sa.gheras.edutrack.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import sa.gheras.edutrack.data.entity.AttendanceExcuseEntity
import java.time.LocalDate

@Dao
interface AttendanceExcuseDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(excuse: AttendanceExcuseEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(excuses: List<AttendanceExcuseEntity>): List<Long>

    @Query("SELECT * FROM attendance_excuses WHERE id = :id")
    suspend fun getById(id: String): AttendanceExcuseEntity?

    @Query("SELECT * FROM attendance_excuses WHERE student_id = :studentId AND excuse_date = :date LIMIT 1")
    suspend fun getByStudentAndDate(studentId: String, date: LocalDate): AttendanceExcuseEntity?

    @Query("SELECT * FROM attendance_excuses WHERE student_id = :studentId ORDER BY excuse_date DESC")
    fun observeByStudent(studentId: String): Flow<List<AttendanceExcuseEntity>>

    @Query("SELECT * FROM attendance_excuses WHERE student_id = :studentId AND excuse_date = :date LIMIT 1")
    fun observeByStudentAndDate(studentId: String, date: LocalDate): Flow<AttendanceExcuseEntity?>

    @Query("SELECT * FROM attendance_excuses ORDER BY excuse_date DESC")
    fun observeAll(): Flow<List<AttendanceExcuseEntity>>

    @Query("SELECT * FROM attendance_excuses WHERE status = :status ORDER BY excuse_date DESC")
    fun observeByStatus(status: String): Flow<List<AttendanceExcuseEntity>>

    @Query("DELETE FROM attendance_excuses WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM attendance_excuses WHERE student_id = :studentId")
    suspend fun clearByStudent(studentId: String)

    @Query("DELETE FROM attendance_excuses")
    suspend fun clear()

    @Transaction
    suspend fun replaceScope(studentId: String, items: List<AttendanceExcuseEntity>) {
        clearByStudent(studentId)
        upsertAll(items)
    }

    @Transaction
    suspend fun replaceAll(items: List<AttendanceExcuseEntity>) {
        clear()
        upsertAll(items)
    }
}
