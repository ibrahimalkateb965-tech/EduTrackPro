package sa.gheras.edutrack.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class StaffAttendanceRecordDto(
    val id: String,
    @SerialName("branch_id")
    val branchId: String? = null,
    @SerialName("staff_id")
    val staffId: String,
    val date: String,
    val status: String,
    @SerialName("check_in_time")
    val checkInTime: String? = null,
    @SerialName("check_out_time")
    val checkOutTime: String? = null,
    @SerialName("check_in_lat")
    val checkInLat: Double? = null,
    @SerialName("check_in_lng")
    val checkInLng: Double? = null,
    @SerialName("check_in_accuracy")
    val checkInAccuracy: Double? = null,
    @SerialName("check_in_distance")
    val checkInDistance: Double? = null,
    @SerialName("check_out_lat")
    val checkOutLat: Double? = null,
    @SerialName("check_out_lng")
    val checkOutLng: Double? = null,
    @SerialName("check_out_accuracy")
    val checkOutAccuracy: Double? = null,
    @SerialName("check_out_distance")
    val checkOutDistance: Double? = null,
    @SerialName("shift_id")
    val shiftId: String? = null,
    @SerialName("shift_name")
    val shiftName: String? = null,
    @SerialName("late_minutes")
    val lateMinutes: Int = 0,
    @SerialName("early_leave_minutes")
    val earlyLeaveMinutes: Int = 0,
    @SerialName("work_duration_minutes")
    val workDurationMinutes: Int = 0,
    @SerialName("is_manual_override")
    val isManualOverride: Boolean = false,
    @SerialName("override_reason")
    val overrideReason: String? = null,
    @SerialName("override_by_user_id")
    val overrideByUserId: String? = null,
    @SerialName("override_at")
    val overrideAt: String? = null,
    @SerialName("deduction_amount")
    val deductionAmount: Double = 0.0,
    val notes: String? = null,
    @SerialName("staff_name")
    val staffName: String? = null,
    @SerialName("role_title")
    val roleTitle: String? = null,
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("updated_at")
    val updatedAt: String? = null
)

@Serializable
data class StaffShiftDto(
    val id: String,
    @SerialName("branch_id")
    val branchId: String? = null,
    val name: String,
    @SerialName("shift_type")
    val shiftType: String = "morning",
    @SerialName("start_time")
    val startTime: String,
    @SerialName("end_time")
    val endTime: String,
    @SerialName("grace_period_minutes")
    val gracePeriodMinutes: Int = 15,
    @SerialName("late_threshold_minutes")
    val lateThresholdMinutes: Int = 30,
    @SerialName("early_leave_threshold_minutes")
    val earlyLeaveThresholdMinutes: Int = 30,
    @SerialName("is_active")
    val isActive: Boolean = true
)

@Serializable
data class GeofenceConfigDto(
    @SerialName("center_lat")
    val centerLat: Double = 24.7136,
    @SerialName("center_lng")
    val centerLng: Double = 46.6753,
    @SerialName("radius_meters")
    val radiusMeters: Double = 200.0,
    @SerialName("max_accuracy_meters")
    val maxAccuracyMeters: Double = 50.0
)

@Serializable
data class StaffStatusResponseDto(
    val date: String? = null,
    val shift: StaffShiftDto? = null,
    val geofence: GeofenceConfigDto = GeofenceConfigDto(),
    val attendance: StaffAttendanceRecordDto? = null
)

@Serializable
data class StaffHistorySummaryDto(
    @SerialName("total_days")
    val totalDays: Int = 0,
    @SerialName("present_days")
    val presentDays: Int = 0,
    @SerialName("late_days")
    val lateDays: Int = 0,
    @SerialName("early_leave_days")
    val earlyLeaveDays: Int = 0,
    @SerialName("absent_days")
    val absentDays: Int = 0,
    @SerialName("total_work_hours")
    val totalWorkHours: Double = 0.0
)

@Serializable
data class StaffHistoryResponseDto(
    val month: String,
    val records: List<StaffAttendanceRecordDto> = emptyList(),
    val summary: StaffHistorySummaryDto = StaffHistorySummaryDto()
)

@Serializable
data class StaffCheckInRequest(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Double
)

@Serializable
data class StaffCheckOutRequest(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Double
)

@Serializable
data class SupervisorOverrideRequest(
    @SerialName("attendance_id")
    val attendanceId: String,
    val status: String,
    @SerialName("override_reason")
    val overrideReason: String,
    @SerialName("check_in_time")
    val checkInTime: String? = null,
    @SerialName("check_out_time")
    val checkOutTime: String? = null,
    val deduction: Double = 0.0,
    val note: String? = null
)
