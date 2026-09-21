package sa.gheras.edutrack.ui.guardian

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import sa.gheras.edutrack.data.dao.AssignmentStudentDao
import sa.gheras.edutrack.data.entity.AssignmentEntity
import sa.gheras.edutrack.data.entity.SubmissionEntity
import sa.gheras.edutrack.data.repo.AssignmentsRepository
import sa.gheras.edutrack.data.repo.StudentsRepository
import java.time.LocalDate

data class ChildAssignmentItem(
    val assignment: AssignmentEntity,
    val submission: SubmissionEntity?,
    val isSubmitted: Boolean
)

data class ChildHomeworkUiState(
    val studentName: String = "",
    val items: List<ChildAssignmentItem> = emptyList(),
    val isLoading: Boolean = true
)

class ChildHomeworkViewModel(
    val studentId: String,
    private val studentsRepository: StudentsRepository,
    private val assignmentsRepository: AssignmentsRepository,
    private val assignmentStudentDao: AssignmentStudentDao
) : ViewModel() {

    val uiState: StateFlow<ChildHomeworkUiState> = combine(
        assignmentStudentDao.observeByStudent(studentId),
        assignmentsRepository.observeAll(),
        assignmentsRepository.observeStudentSubmissions(studentId)
    ) { studentLinks, allAssignments, submissions ->
        val student = studentsRepository.getById(studentId)
        val assignMap = allAssignments.associateBy { it.id }
        val subMap = submissions.associateBy { it.assignmentId }

        val now = LocalDate.now()
        val sixtyDaysAgo = now.minusDays(60)
        val sixtyDaysAhead = now.plusDays(60)

        val items = studentLinks.mapNotNull { link ->
            val assign = assignMap[link.assignmentId] ?: return@mapNotNull null
            // Check ±60 days
            if (assign.dueDate != null && (assign.dueDate.isBefore(sixtyDaysAgo) || assign.dueDate.isAfter(sixtyDaysAhead))) {
                return@mapNotNull null
            }
            val sub = subMap[assign.id]
            ChildAssignmentItem(
                assignment = assign,
                submission = sub,
                isSubmitted = sub != null
            )
        }.sortedByDescending { it.assignment.dueDate ?: LocalDate.MIN }

        ChildHomeworkUiState(
            studentName = student?.name ?: "الطالب",
            items = items,
            isLoading = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChildHomeworkUiState(isLoading = true))
}
