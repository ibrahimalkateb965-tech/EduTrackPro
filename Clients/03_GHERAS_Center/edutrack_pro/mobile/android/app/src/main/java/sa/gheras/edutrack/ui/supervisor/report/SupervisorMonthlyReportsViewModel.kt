package sa.gheras.edutrack.ui.supervisor.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import sa.gheras.edutrack.data.remote.dto.MonthlyReportDto
import sa.gheras.edutrack.data.remote.dto.ReviewReportRequest
import sa.gheras.edutrack.data.repo.MonthlyReportsRepository
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class SupervisorMonthlyReportsUiState(
    val reports: List<MonthlyReportDto> = emptyList(),
    val selectedStatus: String? = "pending_approval", // null = all
    val selectedMonth: String = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM")),
    val selectedRoomId: String? = null,
    val isLoading: Boolean = false,
    val isProcessing: Boolean = false,
    val successMessage: String? = null,
    val errorMessage: String? = null
)

class SupervisorMonthlyReportsViewModel(
    private val monthlyReportsRepository: MonthlyReportsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SupervisorMonthlyReportsUiState())
    val uiState: StateFlow<SupervisorMonthlyReportsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun setStatusFilter(status: String?) {
        _uiState.update { it.copy(selectedStatus = status) }
        refresh()
    }

    fun setMonthFilter(month: String) {
        _uiState.update { it.copy(selectedMonth = month) }
        refresh()
    }

    fun setRoomFilter(roomId: String?) {
        _uiState.update { it.copy(selectedRoomId = roomId) }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val state = _uiState.value
            val result = monthlyReportsRepository.refreshStaffReports(
                roomId = state.selectedRoomId,
                month = state.selectedMonth,
                status = state.selectedStatus
            )
            if (result.isSuccess) {
                _uiState.update {
                    it.copy(
                        reports = result.getOrDefault(emptyList()),
                        isLoading = false
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = result.exceptionOrNull()?.message ?: "فشل تحميل التقارير الشهرية"
                    )
                }
            }
        }
    }

    fun approveReport(reportId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isProcessing = true, errorMessage = null) }
            val result = monthlyReportsRepository.reviewReport(
                reportId = reportId,
                request = ReviewReportRequest(action = "approve")
            )
            if (result.isSuccess) {
                _uiState.update { it.copy(isProcessing = false, successMessage = "تم اعتماد التقرير بنجاح") }
                refresh()
            } else {
                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        errorMessage = result.exceptionOrNull()?.message ?: "فشل اعتماد التقرير"
                    )
                }
            }
        }
    }

    fun requestRevision(reportId: String, notes: String) {
        if (notes.isBlank()) {
            _uiState.update { it.copy(errorMessage = "يرجى كتابة ملاحظات طلب التعديل") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isProcessing = true, errorMessage = null) }
            val result = monthlyReportsRepository.reviewReport(
                reportId = reportId,
                request = ReviewReportRequest(action = "request_revision", revisionNotes = notes)
            )
            if (result.isSuccess) {
                _uiState.update { it.copy(isProcessing = false, successMessage = "تم إرسال طلب التعديل للمعلمة") }
                refresh()
            } else {
                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        errorMessage = result.exceptionOrNull()?.message ?: "فشل إرسال طلب التعديل"
                    )
                }
            }
        }
    }

    fun publishReport(reportId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isProcessing = true, errorMessage = null) }
            val result = monthlyReportsRepository.publishReport(reportId)
            if (result.isSuccess) {
                _uiState.update { it.copy(isProcessing = false, successMessage = "تم نشر التقرير وإشعار ولي الأمر") }
                refresh()
            } else {
                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        errorMessage = result.exceptionOrNull()?.message ?: "فشل نشر التقرير"
                    )
                }
            }
        }
    }

    fun batchPublish(roomId: String) {
        val month = _uiState.value.selectedMonth
        viewModelScope.launch {
            _uiState.update { it.copy(isProcessing = true, errorMessage = null) }
            val result = monthlyReportsRepository.batchPublish(roomId = roomId, month = month)
            if (result.isSuccess) {
                val count = result.getOrNull()?.size ?: 0
                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        successMessage = "تم نشر $count تقريراً بنجاح وإشعار أولياء الأمور"
                    )
                }
                refresh()
            } else {
                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        errorMessage = result.exceptionOrNull()?.message ?: "فشل النشر الدفعي"
                    )
                }
            }
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(successMessage = null, errorMessage = null) }
    }
}
