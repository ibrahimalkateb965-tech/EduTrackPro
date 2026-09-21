package sa.gheras.edutrack.ui.guardian

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import sa.gheras.edutrack.data.entity.EvaluationEntity
import sa.gheras.edutrack.data.entity.InstallmentEntity
import sa.gheras.edutrack.data.entity.StudentAttendanceEntity
import sa.gheras.edutrack.data.entity.StudentEntity
import sa.gheras.edutrack.data.repo.AssignmentsRepository
import sa.gheras.edutrack.data.repo.AttendanceRepository
import sa.gheras.edutrack.data.repo.EvaluationsRepository
import sa.gheras.edutrack.data.repo.FeesRepository
import sa.gheras.edutrack.data.repo.StudentsRepository
import sa.gheras.edutrack.sync.PullSync
import java.time.LocalDate

data class ChildDashboardSummary(
    val selectedChild: StudentEntity? = null,
    val children: List<StudentEntity> = emptyList(),
    val todayAttendance: StudentAttendanceEntity? = null,
    val latestEvaluations: List<EvaluationEntity> = emptyList(),
    val pendingHomeworkCount: Int = 0,
    val nextUnpaidInstallment: InstallmentEntity? = null,
    val isRefreshing: Boolean = false,
    val isLoading: Boolean = true
)

class GuardianHomeViewModel(
    private val studentsRepository: StudentsRepository,
    private val attendanceRepository: AttendanceRepository,
    private val evaluationsRepository: EvaluationsRepository,
    private val assignmentsRepository: AssignmentsRepository,
    private val feesRepository: FeesRepository,
    private val pullSync: PullSync
) : ViewModel() {

    private val _selectedChildId = MutableStateFlow<String?>(null)
    val selectedChildId: StateFlow<String?> = _selectedChildId.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)

    val uiState: StateFlow<ChildDashboardSummary> = combine(
        studentsRepository.observeAll(),
        _selectedChildId,
        _isRefreshing
    ) { allChildren, selectedId, refreshing ->
        Triple(allChildren, selectedId, refreshing)
    }.flatMapLatest { (children, selectedId, refreshing) ->
        if (children.isEmpty()) {
            return@flatMapLatest flowOf(
                ChildDashboardSummary(
                    children = emptyList(),
                    isRefreshing = refreshing,
                    isLoading = false
                )
            )
        }

        val activeChild = children.find { it.id == selectedId } ?: children.first()
        val childId = activeChild.id

        combine(
            attendanceRepository.observeByStudent(childId),
            evaluationsRepository.observeByStudent(childId),
            assignmentsRepository.observeByStudent(childId),
            feesRepository.observeInstallments(childId)
        ) { attendances, evals, assignments, installments ->
            val today = LocalDate.now()
            val todayAtt = attendances.find { it.date == today }
            val latestEvals = evals.distinctBy { it.subject }.take(4)
            val pendingHomework = assignments.count { it.dueDate == null || !it.dueDate.isBefore(today) }
            val nextInstallment = installments
                .filter { it.status != "paid" }
                .minByOrNull { it.dueDate ?: LocalDate.MAX }

            ChildDashboardSummary(
                selectedChild = activeChild,
                children = children,
                todayAttendance = todayAtt,
                latestEvaluations = latestEvals,
                pendingHomeworkCount = pendingHomework,
                nextUnpaidInstallment = nextInstallment,
                isRefreshing = refreshing,
                isLoading = false
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChildDashboardSummary(isLoading = true))

    fun selectChild(childId: String) {
        _selectedChildId.value = childId
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                pullSync.requestFull()
            } finally {
                _isRefreshing.value = false
            }
        }
    }
}
