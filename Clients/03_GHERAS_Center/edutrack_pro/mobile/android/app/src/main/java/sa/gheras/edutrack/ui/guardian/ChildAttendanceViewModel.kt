package sa.gheras.edutrack.ui.guardian

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import sa.gheras.edutrack.data.entity.AttendanceExcuseEntity
import sa.gheras.edutrack.data.entity.StudentAttendanceEntity
import sa.gheras.edutrack.data.repo.AttendanceRepository
import sa.gheras.edutrack.data.repo.StudentsRepository
import java.time.LocalDate

data class ChildAttendanceUiState(
    val studentName: String = "",
    val records: List<StudentAttendanceEntity> = emptyList(),
    val excuses: List<AttendanceExcuseEntity> = emptyList(),
    val presentCount: Int = 0,
    val absentCount: Int = 0,
    val lateCount: Int = 0,
    val excusedCount: Int = 0,
    val isLoading: Boolean = true,
    val isSubmittingExcuse: Boolean = false,
    val excuseError: String? = null
)

class ChildAttendanceViewModel(
    val studentId: String,
    private val studentsRepository: StudentsRepository,
    private val attendanceRepository: AttendanceRepository
) : ViewModel() {

    private val _isSubmitting = MutableStateFlow(false)
    private val _excuseError = MutableStateFlow<String?>(null)

    val uiState: StateFlow<ChildAttendanceUiState> = combine(
        attendanceRepository.observeByStudent(studentId),
        attendanceRepository.observeExcusesByStudent(studentId),
        _isSubmitting,
        _excuseError
    ) { allRecords, excuses, isSubmitting, excuseError ->
        val student = studentsRepository.getById(studentId)
        val sixtyDaysAgo = LocalDate.now().minusDays(60)
        // P4: Never show unapproved draft attendance to guardians
        val filtered = allRecords.filter { !it.date.isBefore(sixtyDaysAgo) && !it.isDraft }

        ChildAttendanceUiState(
            studentName = student?.name ?: "الطالب",
            records = filtered,
            excuses = excuses,
            presentCount = filtered.count { it.status == "حاضر" },
            absentCount = filtered.count { it.status == "غائب" },
            lateCount = filtered.count { it.status == "متأخر" },
            // P3 / G4: Count both "مستأذن" and "غياب بعذر"
            excusedCount = filtered.count { it.status == "مستأذن" || it.status == "غياب بعذر" },
            isLoading = false,
            isSubmittingExcuse = isSubmitting,
            excuseError = excuseError
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChildAttendanceUiState(isLoading = true))

    fun submitExcuse(
        date: LocalDate,
        reasonText: String,
        localFilePath: String? = null,
        onSubmitted: () -> Unit
    ) {
        val trimmed = reasonText.trim()
        if (trimmed.length < 5) {
            _excuseError.value = "سبب العذر يجب ألا يقل عن 5 أحرف"
            return
        }

        viewModelScope.launch {
            _isSubmitting.value = true
            _excuseError.value = null
            try {
                attendanceRepository.submitExcuse(
                    studentId = studentId,
                    excuseDate = date,
                    reasonText = trimmed,
                    localFilePath = localFilePath
                )
                onSubmitted()
            } catch (e: Exception) {
                _excuseError.value = e.message ?: "فشل تقديم العذر"
            } finally {
                _isSubmitting.value = false
            }
        }
    }

    fun patchExcuse(
        excuseId: String,
        reasonText: String? = null,
        localFilePath: String? = null,
        onPatched: () -> Unit
    ) {
        viewModelScope.launch {
            _isSubmitting.value = true
            _excuseError.value = null
            try {
                attendanceRepository.patchExcuse(
                    excuseId = excuseId,
                    reasonText = reasonText?.trim(),
                    localFilePath = localFilePath
                )
                onPatched()
            } catch (e: Exception) {
                _excuseError.value = e.message ?: "فشل استكمال العذر"
            } finally {
                _isSubmitting.value = false
            }
        }
    }

    fun clearError() {
        _excuseError.value = null
    }
}
