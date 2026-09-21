package sa.gheras.edutrack.ui.teacher

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import sa.gheras.edutrack.data.dao.AssignmentStudentDao
import sa.gheras.edutrack.data.dao.RoomDao
import sa.gheras.edutrack.data.entity.AssignmentEntity
import sa.gheras.edutrack.data.entity.StudentEntity
import sa.gheras.edutrack.data.entity.SubmissionEntity
import sa.gheras.edutrack.data.repo.AssignmentsRepository
import sa.gheras.edutrack.data.repo.StudentsRepository

data class StudentSubmissionStatus(
    val student: StudentEntity,
    val submission: SubmissionEntity?,
    val isSubmitted: Boolean
)

data class AssignmentDetailUiState(
    val assignment: AssignmentEntity? = null,
    val roomName: String = "",
    val studentSubmissions: List<StudentSubmissionStatus> = emptyList(),
    val isLoading: Boolean = true
)

class AssignmentDetailViewModel(
    val assignmentId: String,
    private val assignmentsRepository: AssignmentsRepository,
    private val assignmentStudentDao: AssignmentStudentDao,
    private val studentsRepository: StudentsRepository,
    private val roomDao: RoomDao
) : ViewModel() {

    val uiState: StateFlow<AssignmentDetailUiState> = combine(
        assignmentStudentDao.observeByAssignment(assignmentId),
        assignmentsRepository.observeSubmissions(assignmentId),
        studentsRepository.observeAll()
    ) { studentLinks, submissions, allStudents ->
        val assignment = assignmentsRepository.getById(assignmentId)
        val studentMap = allStudents.associateBy { it.id }
        val subMap = submissions.associateBy { it.studentId }

        val studentStatuses = studentLinks.mapNotNull { link ->
            val student = studentMap[link.studentId] ?: return@mapNotNull null
            val submission = subMap[student.id]
            StudentSubmissionStatus(
                student = student,
                submission = submission,
                isSubmitted = submission != null
            )
        }

        val firstStudent = studentStatuses.firstOrNull()?.student
        val room = firstStudent?.roomId?.let { roomDao.getById(it) }

        AssignmentDetailUiState(
            assignment = assignment,
            roomName = room?.name ?: assignment?.subject ?: "حلقة عامة",
            studentSubmissions = studentStatuses,
            isLoading = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AssignmentDetailUiState(isLoading = true))
}
