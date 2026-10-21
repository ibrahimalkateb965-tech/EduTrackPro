package sa.gheras.edutrack.ui.guardian

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import sa.gheras.edutrack.data.dao.AssignmentStudentDao
import sa.gheras.edutrack.data.entity.AssignmentEntity
import sa.gheras.edutrack.data.entity.SubmissionEntity
import sa.gheras.edutrack.data.entity.SubmissionFileEntity
import sa.gheras.edutrack.data.repo.AssignmentsRepository
import sa.gheras.edutrack.data.repo.StudentsRepository
import java.time.LocalDate

enum class HomeworkTab(val labelArabic: String) {
    ALL("الكل"),
    PENDING("مطلوب"),
    SUBMITTED("قيد المراجعة"),
    GRADED("تمت المراجعة"),
    REVISION_REQUESTED("إعادة الحل")
}

data class ChildAssignmentItem(
    val assignment: AssignmentEntity,
    val latestSubmission: SubmissionEntity?,
    val displayStatus: HomeworkTab,
    val files: List<SubmissionFileEntity> = emptyList()
)

data class ChildHomeworkUiState(
    val studentName: String = "",
    val selectedTab: HomeworkTab = HomeworkTab.ALL,
    val items: List<ChildAssignmentItem> = emptyList(),
    val counts: Map<HomeworkTab, Int> = emptyMap(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null
)

class ChildHomeworkViewModel(
    val studentId: String,
    private val studentsRepository: StudentsRepository,
    private val assignmentsRepository: AssignmentsRepository,
    private val assignmentStudentDao: AssignmentStudentDao
) : ViewModel() {

    private val _selectedTab = MutableStateFlow(HomeworkTab.ALL)
    private val _errorMessage = MutableStateFlow<String?>(null)
    private val _tabAndError = combine(_selectedTab, _errorMessage) { tab, err -> tab to err }

    val uiState: StateFlow<ChildHomeworkUiState> = combine(
        assignmentStudentDao.observeByStudent(studentId),
        assignmentsRepository.observeAll(),
        assignmentsRepository.observeLatestByStudent(studentId),
        assignmentsRepository.observeAllSubmissionFiles(),
        _tabAndError
    ) { studentLinks, allAssignments, latestSubmissions, allFiles, (selectedTab, error) ->
        val student = studentsRepository.getById(studentId)
        val assignMap = allAssignments.associateBy { it.id }
        val subMap = latestSubmissions.associateBy { it.assignmentId }
        val filesBySubId = allFiles.groupBy { it.submissionId }

        val allItems = studentLinks.mapNotNull { link ->
            val assign = assignMap[link.assignmentId] ?: return@mapNotNull null
            // Only published assignments for guardians
            if (assign.isDraft) return@mapNotNull null

            val sub = subMap[assign.id]
            val files = sub?.let { filesBySubId[it.id] } ?: emptyList()

            val displayStatus = when {
                sub == null -> HomeworkTab.PENDING
                sub.status == "graded" -> HomeworkTab.GRADED
                sub.status in listOf("revision_requested", "returned") -> HomeworkTab.REVISION_REQUESTED
                else -> HomeworkTab.SUBMITTED
            }

            ChildAssignmentItem(
                assignment = assign,
                latestSubmission = sub,
                displayStatus = displayStatus,
                files = files
            )
        }.sortedWith(
            compareByDescending<ChildAssignmentItem> { it.assignment.dueDate ?: LocalDate.MIN }
                .thenByDescending { it.assignment.createdAt }
        )

        // Compute counts across all items
        val counts = mutableMapOf(
            HomeworkTab.ALL to allItems.size,
            HomeworkTab.PENDING to allItems.count { it.displayStatus == HomeworkTab.PENDING },
            HomeworkTab.SUBMITTED to allItems.count { it.displayStatus == HomeworkTab.SUBMITTED },
            HomeworkTab.GRADED to allItems.count { it.displayStatus == HomeworkTab.GRADED },
            HomeworkTab.REVISION_REQUESTED to allItems.count { it.displayStatus == HomeworkTab.REVISION_REQUESTED }
        )

        // Filter by selected tab
        val filteredItems = if (selectedTab == HomeworkTab.ALL) {
            allItems
        } else {
            allItems.filter { it.displayStatus == selectedTab }
        }

        ChildHomeworkUiState(
            studentName = student?.name ?: "الطالب",
            selectedTab = selectedTab,
            items = filteredItems,
            counts = counts,
            isLoading = false,
            errorMessage = error
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChildHomeworkUiState(isLoading = true))

    fun selectTab(tab: HomeworkTab) {
        _selectedTab.value = tab
    }

    fun clearError() {
        _errorMessage.value = null
    }
}
