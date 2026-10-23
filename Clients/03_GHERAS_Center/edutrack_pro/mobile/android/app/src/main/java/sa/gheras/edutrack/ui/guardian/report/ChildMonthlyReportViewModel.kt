package sa.gheras.edutrack.ui.guardian.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import sa.gheras.edutrack.data.entity.StudentEntity
import sa.gheras.edutrack.data.remote.dto.MonthlyReportDto
import sa.gheras.edutrack.data.repo.MonthlyReportsRepository
import sa.gheras.edutrack.data.repo.StudentsRepository
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class ChildMonthlyReportUiState(
    val student: StudentEntity? = null,
    val selectedMonth: String = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM")),
    val report: MonthlyReportDto? = null,
    val isLoading: Boolean = false,
    val isDownloadingPdf: Boolean = false,
    val pdfUrl: String? = null,
    val errorMessage: String? = null
)

class ChildMonthlyReportViewModel(
    val studentId: String,
    initialMonth: String? = null,
    private val monthlyReportsRepository: MonthlyReportsRepository,
    private val studentsRepository: StudentsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ChildMonthlyReportUiState(
            selectedMonth = initialMonth ?: LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"))
        )
    )
    val uiState: StateFlow<ChildMonthlyReportUiState> = _uiState.asStateFlow()

    init {
        loadStudent()
        refresh()
    }

    private fun loadStudent() {
        viewModelScope.launch {
            val s = studentsRepository.observeById(studentId).firstOrNull()
            _uiState.update { it.copy(student = s) }
        }
    }

    fun selectMonth(month: String) {
        _uiState.update { it.copy(selectedMonth = month) }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, pdfUrl = null) }
            val month = _uiState.value.selectedMonth
            val result = monthlyReportsRepository.refreshGuardianReports(studentId = studentId, month = month)
            if (result.isSuccess) {
                val reports = result.getOrDefault(emptyList())
                // Only published reports are returned to guardians by backend
                val reportForMonth = reports.firstOrNull { it.month == month }
                _uiState.update {
                    it.copy(
                        report = reportForMonth,
                        isLoading = false
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = result.exceptionOrNull()?.message ?: "فشل تحميل التقرير الشهري"
                    )
                }
            }
        }
    }

    fun downloadPdf() {
        val repId = _uiState.value.report?.id ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isDownloadingPdf = true, errorMessage = null) }
            val result = monthlyReportsRepository.fetchReportPdf(repId)
            if (result.isSuccess) {
                _uiState.update {
                    it.copy(
                        isDownloadingPdf = false,
                        pdfUrl = result.getOrNull()?.pdfUrl
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isDownloadingPdf = false,
                        errorMessage = result.exceptionOrNull()?.message ?: "فشل تجهيز ملف PDF"
                    )
                }
            }
        }
    }

    fun clearPdfUrl() {
        _uiState.update { it.copy(pdfUrl = null) }
    }
}
