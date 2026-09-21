package sa.gheras.edutrack.ui.guardian

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import sa.gheras.edutrack.data.entity.StudentAttendanceEntity
import sa.gheras.edutrack.data.repo.AttendanceRepository
import sa.gheras.edutrack.data.repo.StudentsRepository
import java.time.LocalDate

data class ChildAttendanceUiState(
    val studentName: String = "",
    val records: List<StudentAttendanceEntity> = emptyList(),
    val presentCount: Int = 0,
    val absentCount: Int = 0,
    val lateCount: Int = 0,
    val excusedCount: Int = 0,
    val isLoading: Boolean = true
)

class ChildAttendanceViewModel(
    val studentId: String,
    private val studentsRepository: StudentsRepository,
    private val attendanceRepository: AttendanceRepository
) : ViewModel() {

    val uiState: StateFlow<ChildAttendanceUiState> = attendanceRepository.observeByStudent(studentId)
        .map { allRecords ->
            val student = studentsRepository.getById(studentId)
            val sixtyDaysAgo = LocalDate.now().minusDays(60)
            val filtered = allRecords.filter { !it.date.isBefore(sixtyDaysAgo) }

            ChildAttendanceUiState(
                studentName = student?.name ?: "الطالب",
                records = filtered,
                presentCount = filtered.count { it.status == "حاضر" },
                absentCount = filtered.count { it.status == "غائب" },
                lateCount = filtered.count { it.status == "متأخر" },
                excusedCount = filtered.count { it.status == "مستأذن" },
                isLoading = false
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChildAttendanceUiState(isLoading = true))
}
