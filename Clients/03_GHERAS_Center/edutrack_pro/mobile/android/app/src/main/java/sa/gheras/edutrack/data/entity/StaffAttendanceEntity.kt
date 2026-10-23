package sa.gheras.edutrack.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "staff_attendance",
    indices = [
        Index(value = ["staff_id", "date"], unique = true),
        Index(value = ["date"]),
        Index(value = ["status"])
    ]
)
data class StaffAttendanceEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "branch_id")
    val branchId: String? = null,
    @ColumnInfo(name = "staff_id")
    val staffId: String,
    val date: String,
    val status: String,
    @ColumnInfo(name = "check_in_time")
    val checkInTime: String? = null,
    @ColumnInfo(name = "check_out_time")
    val checkOutTime: String? = null,
    @ColumnInfo(name = "check_in_lat")
    val checkInLat: Double? = null,
    @ColumnInfo(name = "check_in_lng")
    val checkInLng: Double? = null,
    @ColumnInfo(name = "check_in_accuracy")
    val checkInAccuracy: Double? = null,
    @ColumnInfo(name = "check_in_distance")
    val checkInDistance: Double? = null,
    @ColumnInfo(name = "check_out_lat")
    val checkOutLat: Double? = null,
    @ColumnInfo(name = "check_out_lng")
    val checkOutLng: Double? = null,
    @ColumnInfo(name = "check_out_accuracy")
    val checkOutAccuracy: Double? = null,
    @ColumnInfo(name = "check_out_distance")
    val checkOutDistance: Double? = null,
    @ColumnInfo(name = "shift_id")
    val shiftId: String? = null,
    @ColumnInfo(name = "late_minutes")
    val lateMinutes: Int = 0,
    @ColumnInfo(name = "early_leave_minutes")
    val earlyLeaveMinutes: Int = 0,
    @ColumnInfo(name = "work_duration_minutes")
    val workDurationMinutes: Int = 0,
    @ColumnInfo(name = "is_manual_override")
    val isManualOverride: Boolean = false,
    @ColumnInfo(name = "override_reason")
    val overrideReason: String? = null,
    @ColumnInfo(name = "override_by_user_id")
    val overrideByUserId: String? = null,
    @ColumnInfo(name = "override_at")
    val overrideAt: String? = null,
    @ColumnInfo(name = "deduction_amount")
    val deductionAmount: Double = 0.0,
    val notes: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: String
)

@Entity(
    tableName = "staff_shifts"
)
data class StaffShiftEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "branch_id")
    val branchId: String? = null,
    val name: String,
    @ColumnInfo(name = "shift_type")
    val shiftType: String = "morning",
    @ColumnInfo(name = "start_time")
    val startTime: String,
    @ColumnInfo(name = "end_time")
    val endTime: String,
    @ColumnInfo(name = "grace_period_minutes")
    val gracePeriodMinutes: Int = 15,
    @ColumnInfo(name = "late_threshold_minutes")
    val lateThresholdMinutes: Int = 30,
    @ColumnInfo(name = "early_leave_threshold_minutes")
    val earlyLeaveThresholdMinutes: Int = 30,
    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true
)
