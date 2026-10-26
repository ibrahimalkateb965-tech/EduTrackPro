package sa.gheras.edutrack.ui.supervisor.attendance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import sa.gheras.edutrack.data.remote.ErrorMapper
import sa.gheras.edutrack.data.remote.dto.StaffAttendanceRecordDto
import sa.gheras.edutrack.data.remote.dto.SupervisorOverrideRequest
import sa.gheras.edutrack.data.repo.StaffAttendanceRepository
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class SupervisorStaffAttendanceUiState(
    val selectedDate: String = LocalDate.now(ZoneId.of("Asia/Riyadh")).format(DateTimeFormatter.ISO_LOCAL_DATE),
    val selectedMonth: String = LocalDate.now(ZoneId.of("Asia/Riyadh")).format(DateTimeFormatter.ofPattern("yyyy-MM")),
    val records: List<StaffAttendanceRecordDto> = emptyList(),
    val totalCount: Int = 0,
    val presentCount: Int = 0,
    val lateCount: Int = 0,
    val absentCount: Int = 0,
    val isLoading: Boolean = false,
    val isOverriding: Boolean = false,
    val selectedRecordForOverride: StaffAttendanceRecordDto? = null,
    val overrideStatus: String = "حاضر",
    val overrideReason: String = "",
    val deductionAmount: String = "0",
    val userMessage: String? = null,
    val errorMessage: String? = null
)

class SupervisorStaffAttendanceViewModel(
    private val staffAttendanceRepository: StaffAttendanceRepository
) : ViewModel() {

    private var currentStaffId: String? = null

    private val _uiState = MutableStateFlow(SupervisorStaffAttendanceUiState())
    val uiState: StateFlow<SupervisorStaffAttendanceUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // The staff id lives on the server, not in the session. A manager
            // without a staff row gets 422 STAFF_NOT_LINKED: keep null, no error.
            staffAttendanceRepository.refreshMyStatus().onSuccess { res ->
                currentStaffId = res.staffId
            }
            loadAttendance()
        }
    }

    fun setDate(date: String) {
        _uiState.update { it.copy(selectedDate = date) }
        loadAttendance()
    }

    fun loadAttendance() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val result = staffAttendanceRepository.listForSupervisor(
                date = _uiState.value.selectedDate,
                month = null
            )
            result.onSuccess { list ->
                val present = list.count { it.status in listOf("حاضر", "present") }
                val late = list.count { it.status in listOf("متأخر", "late") || it.lateMinutes > 0 }
                val absent = list.count { it.status in listOf("غائب", "absent") }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        records = list,
                        totalCount = list.size,
                        presentCount = present,
                        lateCount = late,
                        absentCount = absent
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = ErrorMapper.map(err).userMessage
                    )
                }
            }
        }
    }

    fun openOverrideDialog(record: StaffAttendanceRecordDto) {
        // Enforce Rule M11: Anti-self-approval
        if (record.staffId == currentStaffId) {
            _uiState.update {
                it.copy(errorMessage = "حظر التعديل الذاتي: لا يمكن تعديل سجل حضورك الشخصي بموجب السياسة المعمارية [M11]. يجب أن يتم التعديل بواسطة مديرة المركز.")
            }
            return
        }

        _uiState.update {
            it.copy(
                selectedRecordForOverride = record,
                overrideStatus = record.status,
                overrideReason = "",
                deductionAmount = "${record.deductionAmount}"
            )
        }
    }

    fun closeOverrideDialog() {
        _uiState.update { it.copy(selectedRecordForOverride = null) }
    }

    fun setOverrideStatus(status: String) {
        _uiState.update { it.copy(overrideStatus = status) }
    }

    fun setOverrideReason(reason: String) {
        _uiState.update { it.copy(overrideReason = reason) }
    }

    fun setDeductionAmount(deduction: String) {
        _uiState.update { it.copy(deductionAmount = deduction) }
    }

    fun submitOverride() {
        val target = _uiState.value.selectedRecordForOverride ?: return
        val reason = _uiState.value.overrideReason.trim()
        if (reason.length < 3) {
            _uiState.update { it.copy(errorMessage = "يرجى كتابة سبب التعديل بوضوح (3 أحرف على الأقل).") }
            return
        }

        val deduction = _uiState.value.deductionAmount.toDoubleOrNull() ?: 0.0

        _uiState.update { it.copy(isOverriding = true, errorMessage = null) }
        viewModelScope.launch {
            val req = SupervisorOverrideRequest(
                attendanceId = target.id,
                status = _uiState.value.overrideStatus,
                overrideReason = reason,
                deduction = deduction
            )
            val result = staffAttendanceRepository.overrideAttendance(req)
            result.onSuccess {
                _uiState.update {
                    it.copy(
                        isOverriding = false,
                        selectedRecordForOverride = null,
                        userMessage = "تم تعديل حالة الحضور بنجاح واعتماد العذر."
                    )
                }
                loadAttendance()
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isOverriding = false,
                        errorMessage = ErrorMapper.map(err).userMessage
                    )
                }
            }
        }
    }

    fun dismissMessages() {
        _uiState.update { it.copy(userMessage = null, errorMessage = null) }
    }
}
