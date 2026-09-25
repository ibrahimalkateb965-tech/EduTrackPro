package sa.gheras.edutrack.ui.guardian

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import sa.gheras.edutrack.data.dao.AssignmentStudentDao
import sa.gheras.edutrack.data.entity.AssignmentEntity
import sa.gheras.edutrack.data.entity.SubmissionEntity
import sa.gheras.edutrack.data.entity.SubmissionFileEntity
import sa.gheras.edutrack.data.repo.AssignmentsRepository
import sa.gheras.edutrack.data.repo.StudentsRepository
import sa.gheras.edutrack.homework.HomeworkPhotoProcessor
import java.time.LocalDate

data class ChildAssignmentItem(
    val assignment: AssignmentEntity,
    val submission: SubmissionEntity?,
    val isSubmitted: Boolean,
    val files: List<SubmissionFileEntity> = emptyList()
)

data class ChildHomeworkUiState(
    val studentName: String = "",
    val items: List<ChildAssignmentItem> = emptyList(),
    val isLoading: Boolean = true,
    val isSubmitting: Boolean = false,
    val activeAssignmentId: String? = null,
    val submissionError: String? = null,
    val submissionSuccess: Boolean = false
)

class ChildHomeworkViewModel(
    val studentId: String,
    private val studentsRepository: StudentsRepository,
    private val assignmentsRepository: AssignmentsRepository,
    private val assignmentStudentDao: AssignmentStudentDao
) : ViewModel() {

    private val _isSubmitting = MutableStateFlow(false)
    private val _activeAssignmentId = MutableStateFlow<String?>(null)
    private val _submissionError = MutableStateFlow<String?>(null)
    private val _submissionSuccess = MutableStateFlow(false)

    private data class SubmissionProgressState(
        val isSubmitting: Boolean,
        val activeAssignmentId: String?,
        val submissionError: String?,
        val submissionSuccess: Boolean
    )

    private val _progressState = combine(
        _isSubmitting,
        _activeAssignmentId,
        _submissionError,
        _submissionSuccess
    ) { submitting, activeId, error, success ->
        SubmissionProgressState(submitting, activeId, error, success)
    }

    val uiState: StateFlow<ChildHomeworkUiState> = combine(
        assignmentStudentDao.observeByStudent(studentId),
        assignmentsRepository.observeAll(),
        assignmentsRepository.observeStudentSubmissions(studentId),
        assignmentsRepository.observeAllSubmissionFiles(),
        _progressState
    ) { studentLinks, allAssignments, submissions, allFiles, progress ->
        val student = studentsRepository.getById(studentId)
        val assignMap = allAssignments.associateBy { it.id }
        val subMap = submissions.associateBy { it.assignmentId }
        val filesBySubId = allFiles.groupBy { it.submissionId }

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
            val files = sub?.let { filesBySubId[it.id] } ?: emptyList()
            ChildAssignmentItem(
                assignment = assign,
                submission = sub,
                isSubmitted = sub != null,
                files = files
            )
        }.sortedByDescending { it.assignment.dueDate ?: LocalDate.MIN }

        ChildHomeworkUiState(
            studentName = student?.name ?: "الطالب",
            items = items,
            isLoading = false,
            isSubmitting = progress.isSubmitting,
            activeAssignmentId = progress.activeAssignmentId,
            submissionError = progress.submissionError,
            submissionSuccess = progress.submissionSuccess
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChildHomeworkUiState(isLoading = true))

    fun submitHomework(
        context: Context,
        assignmentId: String,
        imageUris: List<Uri>,
        notes: String? = null
    ) {
        if (imageUris.isEmpty()) {
            _submissionError.value = "يرجى التقاط أو اختيار صورة واحدة على الأقل للواجب"
            return
        }

        viewModelScope.launch {
            _isSubmitting.value = true
            _activeAssignmentId.value = assignmentId
            _submissionError.value = null
            _submissionSuccess.value = false

            try {
                val photoProcessor = HomeworkPhotoProcessor()
                val byteArrayList = photoProcessor.processPhotos(context, imageUris)

                if (byteArrayList.isEmpty()) {
                    _submissionError.value = "تعذر معالجة وضغط الصور المختارة. يرجى المحاولة مرة أخرى"
                    _isSubmitting.value = false
                    _activeAssignmentId.value = null
                    return@launch
                }

                val result = assignmentsRepository.submitHomework(
                    assignmentId = assignmentId,
                    studentId = studentId,
                    imageBytesList = byteArrayList,
                    notes = notes
                )

                if (result.isSuccess) {
                    _submissionSuccess.value = true
                } else {
                    _submissionError.value = result.exceptionOrNull()?.message ?: "فشل في رفع الواجب، يرجى التحقق من الاتصال بالإنترنت"
                }
            } catch (e: Exception) {
                _submissionError.value = e.message ?: "حدث خطأ غير متوقع أثناء الرفع"
            } finally {
                _isSubmitting.value = false
                _activeAssignmentId.value = null
            }
        }
    }

    fun clearStatus() {
        _submissionError.value = null
        _submissionSuccess.value = false
    }
}
