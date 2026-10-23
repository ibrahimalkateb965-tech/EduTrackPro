package sa.gheras.edutrack.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import sa.gheras.edutrack.data.entity.StaffAttendanceEntity
import sa.gheras.edutrack.data.entity.StaffShiftEntity

@Dao
interface StaffAttendanceDao {

    @Query("SELECT * FROM staff_attendance WHERE staff_id = :staffId AND date LIKE :monthPrefix || '%' ORDER BY date DESC")
    fun getMyAttendanceForMonth(staffId: String, monthPrefix: String): Flow<List<StaffAttendanceEntity>>

    @Query("SELECT * FROM staff_attendance WHERE staff_id = :staffId AND date = :date LIMIT 1")
    fun getTodayAttendance(staffId: String, date: String): Flow<StaffAttendanceEntity?>

    @Query("SELECT * FROM staff_attendance WHERE date = :date ORDER BY check_in_time ASC")
    fun getAttendanceByDate(date: String): Flow<List<StaffAttendanceEntity>>

    @Query("SELECT * FROM staff_attendance WHERE date LIKE :monthPrefix || '%' ORDER BY date DESC, check_in_time ASC")
    fun getAttendanceForMonth(monthPrefix: String): Flow<List<StaffAttendanceEntity>>

    @Query("SELECT * FROM staff_attendance WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): StaffAttendanceEntity?

    @Upsert
    suspend fun upsert(records: List<StaffAttendanceEntity>)

    @Upsert
    suspend fun upsertOne(record: StaffAttendanceEntity)

    @Query("SELECT * FROM staff_shifts WHERE is_active = 1")
    fun getActiveShifts(): Flow<List<StaffShiftEntity>>

    @Query("SELECT * FROM staff_shifts WHERE id = :shiftId LIMIT 1")
    suspend fun getShiftById(shiftId: String): StaffShiftEntity?

    @Upsert
    suspend fun upsertShifts(shifts: List<StaffShiftEntity>)
}
