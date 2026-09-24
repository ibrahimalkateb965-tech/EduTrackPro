package sa.gheras.edutrack.ui.teacher

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import sa.gheras.edutrack.data.dao.AssignmentStudentDao
import sa.gheras.edutrack.data.dao.RoomDao
import sa.gheras.edutrack.data.entity.AssignmentEntity
import sa.gheras.edutrack.data.entity.StudentEntity
import sa.gheras.edutrack.data.entity.SubmissionEntity
import sa.gheras.edutrack.data.repo.AssignmentsRepository
import sa.gheras.edutrack.data.repo.StudentsRepository
import sa.gheras.edutrack.sync.PullSync

enum class SubmissionFilter(val title: String) {
    ALL("الكل"),
    SUBMITTED("مُسلَّم"),
    PENDING("لم يُسلَّم")
}

data class StudentSubmissionStatus(
    val student: StudentEntity,
    val submission: SubmissionEntity?,
    val isSubmitted: Boolean
)

data class AssignmentDetailUiState(
    val assignment: AssignmentEntity? = null,
    val roomName: String = "",
    val studentSubmissions: List<StudentSubmissionStatus> = emptyList(),
    val totalCount: Int = 0,
    val submittedCount: Int = 0,
    val pendingCount: Int = 0,
    val filter: SubmissionFilter = SubmissionFilter.ALL,
    val isRefreshing: Boolean = false,
    val isLoading: Boolean = true
)

class AssignmentDetailViewModel(
    val assignmentId: String,
    private val assignmentsRepository: AssignmentsRepository,
    private val assignmentStudentDao: AssignmentStudentDao,
    private val studentsRepository: StudentsRepository,
    private val roomDao: RoomDao,
    private val pullSync: PullSync? = null
) : ViewModel() {

    private val _filter = MutableStateFlow(SubmissionFilter.ALL)
    private val _isRefreshing = MutableStateFlow(false)

    val uiState: StateFlow<AssignmentDetailUiState> = combine(
        assignmentStudentDao.observeByAssignment(assignmentId),
        assignmentsRepository.observeSubmissions(assignmentId),
        studentsRepository.observeAll(),
        _filter,
        _isRefreshing
    ) { studentLinks, submissions, allStudents, filter, isRefreshing ->
        val assignment = assignmentsRepository.getById(assignmentId)
        val studentMap = allStudents.associateBy { it.id }
        val subMap = submissions.associateBy { it.studentId }

        val allStatuses = studentLinks.mapNotNull { link ->
            val student = studentMap[link.studentId] ?: return@mapNotNull null
            val submission = subMap[student.id]
            StudentSubmissionStatus(
                student = student,
                submission = submission,
                isSubmitted = submission != null
            )
        }

        val firstStudent = allStatuses.firstOrNull()?.student
        val room = firstStudent?.roomId?.let { roomDao.getById(it) }

        val submittedCount = allStatuses.count { it.isSubmitted }
        val pendingCount = allStatuses.size - submittedCount

        val filteredStatuses = when (filter) {
            SubmissionFilter.ALL -> allStatuses
            SubmissionFilter.SUBMITTED -> allStatuses.filter { it.isSubmitted }
            SubmissionFilter.PENDING -> allStatuses.filter { !it.isSubmitted }
        }

        AssignmentDetailUiState(
            assignment = assignment,
            roomName = room?.name ?: assignment?.subject ?: "قاعة عامة",
            studentSubmissions = filteredStatuses,
            totalCount = allStatuses.size,
            submittedCount = submittedCount,
            pendingCount = pendingCount,
            filter = filter,
            isRefreshing = isRefreshing,
            isLoading = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AssignmentDetailUiState(isLoading = true))

    fun setFilter(filter: SubmissionFilter) {
        _filter.value = filter
    }

    fun refresh() {
        if (_isRefreshing.value || pullSync == null) return
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                pullSync.sync()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                _isRefreshing.value = false
            }
        }
    }
}
