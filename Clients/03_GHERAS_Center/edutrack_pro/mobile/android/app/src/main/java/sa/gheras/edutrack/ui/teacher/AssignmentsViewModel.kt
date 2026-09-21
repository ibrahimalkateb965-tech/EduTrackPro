package sa.gheras.edutrack.ui.teacher

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import sa.gheras.edutrack.data.dao.AssignmentDao
import sa.gheras.edutrack.data.dao.RoomDao
import sa.gheras.edutrack.data.entity.AssignmentEntity
import sa.gheras.edutrack.data.repo.AssignmentsRepository
import java.time.LocalDate

data class AssignmentItem(
    val assignment: AssignmentEntity,
    val roomName: String
)

data class AssignmentsUiState(
    val assignments: List<AssignmentItem> = emptyList(),
    val isLoading: Boolean = true
)

class AssignmentsViewModel(
    private val assignmentsRepository: AssignmentsRepository,
    private val roomDao: RoomDao
) : ViewModel() {

    val uiState: StateFlow<AssignmentsUiState> = combine(
        assignmentsRepository.observeAll(),
        roomDao.observeAll()
    ) { assignments, rooms ->
        val roomMap = rooms.associateBy { it.id }
        val now = LocalDate.now()
        val sixtyDaysAgo = now.minusDays(60)
        val sixtyDaysAhead = now.plusDays(60)

        val filtered = assignments.filter {
            it.dueDate == null || (!it.dueDate.isBefore(sixtyDaysAgo) && !it.dueDate.isAfter(sixtyDaysAhead))
        }.sortedByDescending { it.dueDate ?: LocalDate.MIN }
        .map { assignment ->
            AssignmentItem(
                assignment = assignment,
                roomName = assignment.subject ?: "حلقة عامة"
            )
        }

        AssignmentsUiState(
            assignments = filtered,
            isLoading = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AssignmentsUiState(isLoading = true))
}
