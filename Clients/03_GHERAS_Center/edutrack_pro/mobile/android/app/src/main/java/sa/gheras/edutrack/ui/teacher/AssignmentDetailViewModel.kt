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
import sa.gheras.edutrack.data.entity.SubmissionFileEntity
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
    val isSubmitted: Boolean,
    val files: List<SubmissionFileEntity> = emptyList()
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
    val isLoading: Boolean = true,
    val isGrading: Boolean = false,
    val gradingError: String? = null
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
    private val _isGrading = MutableStateFlow(false)
    private val _gradingError = MutableStateFlow<String?>(null)

    private data class CombinedFilterState(
        val filter: SubmissionFilter,
        val isRefreshing: Boolean,
        val isGrading: Boolean,
        val gradingError: String?
    )

    private val _combinedState = combine(
        _filter,
        _isRefreshing,
        _isGrading,
        _gradingError
    ) { filter, refreshing, grading, error ->
        CombinedFilterState(filter, refreshing, grading, error)
    }

    val uiState: StateFlow<AssignmentDetailUiState> = combine(
        assignmentStudentDao.observeByAssignment(assignmentId),
        assignmentsRepository.observeSubmissions(assignmentId),
        assignmentsRepository.observeAllSubmissionFiles(),
        studentsRepository.observeAll(),
        _combinedState
    ) { studentLinks, submissions, allFiles, allStudents, controlState ->
        val assignment = assignmentsRepository.getById(assignmentId)
        val studentMap = allStudents.associateBy { it.id }
        val subMap = submissions.associateBy { it.studentId }
        val filesBySubId = allFiles.groupBy { it.submissionId }

        val allStatuses = studentLinks.mapNotNull { link ->
            val student = studentMap[link.studentId] ?: return@mapNotNull null
            val submission = subMap[student.id]
            val files = submission?.let { filesBySubId[it.id] } ?: emptyList()
            StudentSubmissionStatus(
                student = student,
                submission = submission,
                isSubmitted = submission != null,
                files = files
            )
        }

        val firstStudent = allStatuses.firstOrNull()?.student
        val room = firstStudent?.roomId?.let { roomDao.getById(it) }

        val submittedCount = allStatuses.count { it.isSubmitted }
        val pendingCount = allStatuses.size - submittedCount

        val filteredStatuses = when (controlState.filter) {
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
            filter = controlState.filter,
            isRefreshing = controlState.isRefreshing,
            isLoading = false,
            isGrading = controlState.isGrading,
            gradingError = controlState.gradingError
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AssignmentDetailUiState(isLoading = true))

    fun setFilter(filter: SubmissionFilter) {
        _filter.value = filter
    }

    fun gradeSubmission(submissionId: String, grade: Double, feedback: String?) {
        viewModelScope.launch {
            _isGrading.value = true
            _gradingError.value = null
            try {
                val res = assignmentsRepository.gradeSubmission(submissionId, grade, feedback)
                if (res.isFailure) {
                    _gradingError.value = res.exceptionOrNull()?.message ?: "فشل في تسجيل الدرجة"
                }
            } catch (e: Exception) {
                _gradingError.value = e.message ?: "حدث خطأ غير متوقع أثناء التقييم"
            } finally {
                _isGrading.value = false
            }
        }
    }

    fun clearGradingError() {
        _gradingError.value = null
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
