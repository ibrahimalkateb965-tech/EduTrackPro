package sa.gheras.edutrack.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import sa.gheras.edutrack.data.entity.MonthlyStudentReportEntity

@Dao
interface MonthlyReportDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReport(report: MonthlyStudentReportEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReports(reports: List<MonthlyStudentReportEntity>): List<Long>

    @Query("SELECT * FROM monthly_student_reports WHERE id = :id")
    suspend fun getReportById(id: String): MonthlyStudentReportEntity?

    @Query("SELECT * FROM monthly_student_reports WHERE id = :id")
    fun observeReportById(id: String): Flow<MonthlyStudentReportEntity?>

    @Query("SELECT * FROM monthly_student_reports WHERE student_id = :studentId ORDER BY month DESC")
    fun observeReportsByStudent(studentId: String): Flow<List<MonthlyStudentReportEntity>>

    @Query("SELECT * FROM monthly_student_reports WHERE student_id = :studentId AND month = :month LIMIT 1")
    fun observeReportByStudentAndMonth(studentId: String, month: String): Flow<MonthlyStudentReportEntity?>

    @Query("SELECT * FROM monthly_student_reports WHERE room_id = :roomId AND month = :month ORDER BY created_at DESC")
    fun observeReportsByRoomAndMonth(roomId: String, month: String): Flow<List<MonthlyStudentReportEntity>>

    @Query("SELECT * FROM monthly_student_reports WHERE room_id = :roomId AND status = 'pending_approval' ORDER BY submitted_at ASC")
    fun observePendingReportsByRoom(roomId: String): Flow<List<MonthlyStudentReportEntity>>

    @Query("SELECT * FROM monthly_student_reports WHERE status = 'pending_approval' ORDER BY submitted_at ASC")
    fun observeAllPendingReports(): Flow<List<MonthlyStudentReportEntity>>

    @Query("DELETE FROM monthly_student_reports WHERE id = :id")
    suspend fun deleteReportById(id: String)

    @Query("DELETE FROM monthly_student_reports WHERE student_id = :studentId")
    suspend fun clearReportsByStudent(studentId: String)

    @Query("DELETE FROM monthly_student_reports")
    suspend fun clearReports()
}
