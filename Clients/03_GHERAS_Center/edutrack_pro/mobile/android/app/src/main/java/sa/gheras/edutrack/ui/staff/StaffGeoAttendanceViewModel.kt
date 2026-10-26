package sa.gheras.edutrack.ui.staff

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import sa.gheras.edutrack.data.entity.StaffAttendanceEntity
import sa.gheras.edutrack.data.remote.ErrorMapper
import sa.gheras.edutrack.data.repo.StaffAttendanceRepository
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class StaffGeoAttendanceUiState(
    val isLoading: Boolean = false,
    val isPunchedIn: Boolean = false,
    val isPunchedOut: Boolean = false,
    val checkInTime: String? = null,
    val checkOutTime: String? = null,
    val todayStatus: String? = null,
    val lateMinutes: Int = 0,
    val workDurationMinutes: Int = 0,
    // Geofence & Location
    val centerLat: Double = 24.7136,
    val centerLng: Double = 46.6753,
    val currentLat: Double = 24.7136,
    val currentLng: Double = 46.6753,
    val hasLocationFix: Boolean = false,
    val isMockLocation: Boolean = false,
    val locationPermissionDenied: Boolean = false,
    val currentAccuracy: Double = 0.0,
    val distanceMeters: Double = 0.0,
    val isInsideGeofence: Boolean = false,
    val isAccuracyAcceptable: Boolean = false,
    val maxAccuracyMeters: Double = 50.0,
    val geofenceRadiusMeters: Double = 200.0,
    val activeShiftName: String? = null,
    val activeShiftTimes: String? = null,
    // History
    val historyMonth: String = LocalDate.now(ZoneId.of("Asia/Riyadh")).format(DateTimeFormatter.ofPattern("yyyy-MM")),
    val historyRecords: List<StaffAttendanceEntity> = emptyList(),
    val totalDays: Int = 0,
    val presentDays: Int = 0,
    val lateDays: Int = 0,
    val totalWorkHours: Double = 0.0,
    // Action status
    val isActionInProgress: Boolean = false,
    val userMessage: String? = null,
    val errorMessage: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class StaffGeoAttendanceViewModel(
    private val staffAttendanceRepository: StaffAttendanceRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(StaffGeoAttendanceUiState())
    val uiState: StateFlow<StaffGeoAttendanceUiState> = _uiState.asStateFlow()

    private val staffIdFlow = MutableStateFlow<String?>(null)
    private val todayDateFlow = MutableStateFlow<String?>(null)

    init {
        observeLocalAttendance()
        refreshAll()
    }

    private fun observeLocalAttendance() {
        viewModelScope.launch {
            combine(staffIdFlow.filterNotNull(), todayDateFlow.filterNotNull()) { staffId, date ->
                staffId to date
            }.flatMapLatest { (staffId, date) ->
                staffAttendanceRepository.observeTodayAttendance(staffId, date)
            }.collect { att ->
                if (att != null) {
                    _uiState.update { current ->
                        current.copy(
                            isPunchedIn = att.checkInTime != null,
                            isPunchedOut = att.checkOutTime != null,
                            checkInTime = att.checkInTime,
                            checkOutTime = att.checkOutTime,
                            todayStatus = att.status,
                            lateMinutes = att.lateMinutes,
                            workDurationMinutes = att.workDurationMinutes
                        )
                    }
                }
            }
        }

        viewModelScope.launch {
            staffIdFlow.filterNotNull().flatMapLatest { staffId ->
                staffAttendanceRepository.observeMyAttendanceForMonth(staffId, _uiState.value.historyMonth)
            }.collect { records ->
                val present = records.count { it.status in listOf("حاضر", "present") }
                val late = records.count { it.status in listOf("متأخر", "late") || it.lateMinutes > 0 }
                val hours = records.sumOf { it.workDurationMinutes } / 60.0
                _uiState.update { current ->
                    current.copy(
                        historyRecords = records,
                        totalDays = records.size,
                        presentDays = present,
                        lateDays = late,
                        totalWorkHours = (hours * 10).toInt() / 10.0
                    )
                }
            }
        }
    }

    fun refreshAll() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            staffAttendanceRepository.refreshMyStatus()
                .onSuccess { res ->
                    staffIdFlow.value = res.staffId
                    todayDateFlow.value = res.date
                    val geo = res.geofence
                    val shift = res.shift
                    val att = res.attendance
                    _uiState.update { current ->
                        val hasFix = current.hasLocationFix
                        val dist = if (hasFix) {
                            calculateDistance(current.currentLat, current.currentLng, geo.centerLat, geo.centerLng)
                        } else {
                            current.distanceMeters
                        }
                        current.copy(
                            centerLat = geo.centerLat,
                            centerLng = geo.centerLng,
                            geofenceRadiusMeters = geo.radiusMeters,
                            maxAccuracyMeters = geo.maxAccuracyMeters,
                            distanceMeters = dist,
                            isInsideGeofence = hasFix && dist <= geo.radiusMeters,
                            isAccuracyAcceptable = hasFix && current.currentAccuracy <= geo.maxAccuracyMeters,
                            activeShiftName = shift?.name ?: "الدوام الرسمي",
                            activeShiftTimes = if (shift != null) "${shift.startTime} - ${shift.endTime}" else "07:30 - 13:30",
                            isPunchedIn = att?.checkInTime != null,
                            isPunchedOut = att?.checkOutTime != null,
                            checkInTime = att?.checkInTime,
                            checkOutTime = att?.checkOutTime,
                            todayStatus = att?.status
                        )
                    }

                    // 2. Refresh monthly history
                    staffAttendanceRepository.refreshMyHistory(_uiState.value.historyMonth)
                    _uiState.update { it.copy(isLoading = false) }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(isLoading = false, errorMessage = ErrorMapper.map(err).userMessage)
                    }
                    return@launch
                }
        }
    }

    fun updateLocation(lat: Double, lng: Double, accuracy: Double, isMock: Boolean = false) {
        val dist = calculateDistance(lat, lng, _uiState.value.centerLat, _uiState.value.centerLng)
        val inside = dist <= _uiState.value.geofenceRadiusMeters
        val accurate = accuracy <= _uiState.value.maxAccuracyMeters
        _uiState.update { current ->
            current.copy(
                currentLat = lat,
                currentLng = lng,
                currentAccuracy = accuracy,
                hasLocationFix = true,
                isMockLocation = isMock,
                distanceMeters = dist,
                isInsideGeofence = inside,
                isAccuracyAcceptable = accurate
            )
        }
    }

    fun onLocationPermissionDenied() {
        _uiState.update { it.copy(locationPermissionDenied = true) }
    }

    private fun guardPunch(): Boolean {
        if (!_uiState.value.hasLocationFix) {
            _uiState.update {
                it.copy(errorMessage = "جاري تحديد موقعك… يرجى الانتظار حتى يظهر موقعك الحالي.")
            }
            return false
        }
        if (_uiState.value.isMockLocation) {
            _uiState.update {
                it.copy(errorMessage = "تم رصد موقع وهمي. يرجى إيقاف تطبيقات تزييف الموقع ثم المحاولة.")
            }
            return false
        }
        if (!_uiState.value.isInsideGeofence) {
            _uiState.update {
                it.copy(errorMessage = "أنت خارج نطاق مركز غراس الجغرافي (${it.distanceMeters.toInt()}م من المركز). يجب أن تكون داخل نطاق ${it.geofenceRadiusMeters.toInt()}م.")
            }
            return false
        }
        if (!_uiState.value.isAccuracyAcceptable) {
            _uiState.update {
                it.copy(errorMessage = "دقة نظام GPS غير كافية (${it.currentAccuracy.toInt()}م). الحد المقبول ${it.maxAccuracyMeters.toInt()}م. يرجى التوجه لمكان مكشوف.")
            }
            return false
        }
        return true
    }

    fun punchCheckIn() {
        if (!guardPunch()) return

        _uiState.update { it.copy(isActionInProgress = true, errorMessage = null, userMessage = null) }
        viewModelScope.launch {
            val result = staffAttendanceRepository.checkIn(
                latitude = _uiState.value.currentLat,
                longitude = _uiState.value.currentLng,
                accuracy = _uiState.value.currentAccuracy
            )
            result.onSuccess { rec ->
                _uiState.update {
                    it.copy(
                        isActionInProgress = false,
                        isPunchedIn = true,
                        checkInTime = rec.checkInTime,
                        todayStatus = rec.status,
                        userMessage = "تم تسجيل الحضور بنجاح (${rec.status})"
                    )
                }
                refreshAll()
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isActionInProgress = false,
                        errorMessage = ErrorMapper.map(err).userMessage
                    )
                }
            }
        }
    }

    fun punchCheckOut() {
        if (!guardPunch()) return

        _uiState.update { it.copy(isActionInProgress = true, errorMessage = null, userMessage = null) }
        viewModelScope.launch {
            val result = staffAttendanceRepository.checkOut(
                latitude = _uiState.value.currentLat,
                longitude = _uiState.value.currentLng,
                accuracy = _uiState.value.currentAccuracy
            )
            result.onSuccess { rec ->
                _uiState.update {
                    it.copy(
                        isActionInProgress = false,
                        isPunchedOut = true,
                        checkOutTime = rec.checkOutTime,
                        workDurationMinutes = rec.workDurationMinutes,
                        userMessage = "تم تسجيل الانصراف بنجاح. مدة العمل: ${rec.workDurationMinutes / 60} ساعة و${rec.workDurationMinutes % 60} دقيقة."
                    )
                }
                refreshAll()
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isActionInProgress = false,
                        errorMessage = ErrorMapper.map(err).userMessage
                    )
                }
            }
        }
    }

    fun dismissMessages() {
        _uiState.update { it.copy(userMessage = null, errorMessage = null) }
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaPhi = Math.toRadians(lat2 - lat1)
        val deltaLambda = Math.toRadians(lon2 - lon1)
        val a = sin(deltaPhi / 2.0).pow(2.0) +
                cos(phi1) * cos(phi2) * sin(deltaLambda / 2.0).pow(2.0)
        val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
        return r * c
    }
}
