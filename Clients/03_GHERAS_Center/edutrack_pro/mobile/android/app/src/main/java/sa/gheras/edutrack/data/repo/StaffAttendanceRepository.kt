package sa.gheras.edutrack.data.repo

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import sa.gheras.edutrack.data.dao.StaffAttendanceDao
import sa.gheras.edutrack.data.entity.StaffAttendanceEntity
import sa.gheras.edutrack.data.entity.StaffShiftEntity
import sa.gheras.edutrack.data.remote.StaffAttendanceApi
import sa.gheras.edutrack.data.remote.dto.StaffAttendanceRecordDto
import sa.gheras.edutrack.data.remote.dto.StaffCheckInRequest
import sa.gheras.edutrack.data.remote.dto.StaffCheckOutRequest
import sa.gheras.edutrack.data.remote.dto.StaffHistoryResponseDto
import sa.gheras.edutrack.data.remote.dto.StaffShiftDto
import sa.gheras.edutrack.data.remote.dto.StaffStatusResponseDto
import sa.gheras.edutrack.data.remote.dto.SupervisorOverrideRequest
import java.time.Instant

class StaffAttendanceRepository(
    private val staffAttendanceDao: StaffAttendanceDao,
    private val staffAttendanceApi: StaffAttendanceApi,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    fun observeMyAttendanceForMonth(staffId: String, monthPrefix: String): Flow<List<StaffAttendanceEntity>> {
        return staffAttendanceDao.getMyAttendanceForMonth(staffId, monthPrefix)
    }

    fun observeTodayAttendance(staffId: String, date: String): Flow<StaffAttendanceEntity?> {
        return staffAttendanceDao.getTodayAttendance(staffId, date)
    }

    fun observeAttendanceByDate(date: String): Flow<List<StaffAttendanceEntity>> {
        return staffAttendanceDao.getAttendanceByDate(date)
    }

    fun observeShifts(): Flow<List<StaffShiftEntity>> {
        return staffAttendanceDao.getActiveShifts()
    }

    suspend fun checkIn(
        latitude: Double,
        longitude: Double,
        accuracy: Double
    ): Result<StaffAttendanceRecordDto> = withContext(ioDispatcher) {
        runCatching {
            val response = staffAttendanceApi.checkIn(
                StaffCheckInRequest(latitude = latitude, longitude = longitude, accuracy = accuracy)
            )
            staffAttendanceDao.upsertOne(response.toEntity())
            response
        }
    }

    suspend fun checkOut(
        latitude: Double,
        longitude: Double,
        accuracy: Double
    ): Result<StaffAttendanceRecordDto> = withContext(ioDispatcher) {
        runCatching {
            val response = staffAttendanceApi.checkOut(
                StaffCheckOutRequest(latitude = latitude, longitude = longitude, accuracy = accuracy)
            )
            staffAttendanceDao.upsertOne(response.toEntity())
            response
        }
    }

    suspend fun refreshMyStatus(): Result<StaffStatusResponseDto> = withContext(ioDispatcher) {
        runCatching {
            val response = staffAttendanceApi.getMyStatus()
            response.attendance?.let {
                staffAttendanceDao.upsertOne(it.toEntity())
            }
            response.shift?.let {
                staffAttendanceDao.upsertShifts(listOf(it.toEntity()))
            }
            response
        }
    }

    suspend fun refreshMyHistory(month: String? = null): Result<StaffHistoryResponseDto> = withContext(ioDispatcher) {
        runCatching {
            val response = staffAttendanceApi.getMyHistory(month)
            if (response.records.isNotEmpty()) {
                val entities = response.records.map { it.toEntity() }
                staffAttendanceDao.upsert(entities)
            }
            response
        }
    }

    suspend fun listForSupervisor(
        date: String? = null,
        month: String? = null,
        staffId: String? = null
    ): Result<List<StaffAttendanceRecordDto>> = withContext(ioDispatcher) {
        runCatching {
            val records = staffAttendanceApi.listForSupervisor(date = date, month = month, staffId = staffId)
            if (records.isNotEmpty()) {
                val entities = records.map { it.toEntity() }
                staffAttendanceDao.upsert(entities)
            }
            records
        }
    }

    suspend fun overrideAttendance(
        request: SupervisorOverrideRequest
    ): Result<StaffAttendanceRecordDto> = withContext(ioDispatcher) {
        runCatching {
            val updated = staffAttendanceApi.overrideAttendance(request)
            staffAttendanceDao.upsertOne(updated.toEntity())
            updated
        }
    }

    private fun StaffAttendanceRecordDto.toEntity(): StaffAttendanceEntity {
        val now = Instant.now().toString()
        return StaffAttendanceEntity(
            id = id,
            branchId = branchId,
            staffId = staffId,
            date = date,
            status = status,
            checkInTime = checkInTime,
            checkOutTime = checkOutTime,
            checkInLat = checkInLat,
            checkInLng = checkInLng,
            checkInAccuracy = checkInAccuracy,
            checkInDistance = checkInDistance,
            checkOutLat = checkOutLat,
            checkOutLng = checkOutLng,
            checkOutAccuracy = checkOutAccuracy,
            checkOutDistance = checkOutDistance,
            shiftId = shiftId,
            lateMinutes = lateMinutes,
            earlyLeaveMinutes = earlyLeaveMinutes,
            workDurationMinutes = workDurationMinutes,
            isManualOverride = isManualOverride,
            overrideReason = overrideReason,
            overrideByUserId = overrideByUserId,
            overrideAt = overrideAt,
            deductionAmount = deductionAmount,
            notes = notes,
            createdAt = createdAt ?: now,
            updatedAt = updatedAt ?: now
        )
    }

    private fun StaffShiftDto.toEntity(): StaffShiftEntity {
        return StaffShiftEntity(
            id = id,
            branchId = branchId,
            name = name,
            shiftType = shiftType,
            startTime = startTime,
            endTime = endTime,
            gracePeriodMinutes = gracePeriodMinutes,
            lateThresholdMinutes = lateThresholdMinutes,
            earlyLeaveThresholdMinutes = earlyLeaveThresholdMinutes,
            isActive = isActive
        )
    }
}
