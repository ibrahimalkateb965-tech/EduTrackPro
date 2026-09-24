package sa.gheras.edutrack.ui.teacher

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import sa.gheras.edutrack.data.dao.AssignmentStudentDao
import sa.gheras.edutrack.data.dao.RoomDao
import sa.gheras.edutrack.data.dao.SubmissionDao
import sa.gheras.edutrack.data.entity.AssignmentEntity
import sa.gheras.edutrack.data.entity.AssignmentStudentEntity
import sa.gheras.edutrack.data.entity.RoomEntity
import sa.gheras.edutrack.data.entity.StudentEntity
import sa.gheras.edutrack.data.entity.SubmissionEntity
import sa.gheras.edutrack.data.local.session.Role
import sa.gheras.edutrack.data.local.session.SessionStore
import sa.gheras.edutrack.data.repo.AssignmentsRepository
import sa.gheras.edutrack.data.repo.StudentsRepository
import sa.gheras.edutrack.sync.PullSync
import java.time.LocalDate

enum class AssignmentStatusFilter(val title: String) {
    ALL("الكل"),
    ACTIVE("قيد التسليم"),
    COMPLETED("مكتملة"),
    PAST_DUE("متأخرة")
}

data class AssignmentItem(
    val assignment: AssignmentEntity,
    val roomName: String,
    val roomId: String? = null,
    val assignedCount: Int = 0,
    val submittedCount: Int = 0,
    val isCompleted: Boolean = false,
    val isPastDue: Boolean = false
)

data class AssignmentsUiState(
    val assignments: List<AssignmentItem> = emptyList(),
    val rooms: List<RoomEntity> = emptyList(),
    val selectedRoomId: String? = null,
    val statusFilter: AssignmentStatusFilter = AssignmentStatusFilter.ALL,
    val searchQuery: String = "",
    val isRefreshing: Boolean = false,
    val isLoading: Boolean = true,
    val isScopedToAssigned: Boolean = false,
    val totalCount: Int = 0,
    val activeCount: Int = 0,
    val completedCount: Int = 0
)

private data class AssignmentData(
    val assignments: List<AssignmentEntity>,
    val rooms: List<RoomEntity>,
    val links: List<AssignmentStudentEntity>,
    val students: List<StudentEntity>,
    val submissions: List<SubmissionEntity>
)

class AssignmentsViewModel(
    private val assignmentsRepository: AssignmentsRepository,
    private val roomDao: RoomDao,
    private val assignmentStudentDao: AssignmentStudentDao? = null,
    private val studentsRepository: StudentsRepository? = null,
    private val submissionDao: SubmissionDao? = null,
    private val sessionStore: SessionStore? = null,
    private val pullSync: PullSync? = null
) : ViewModel() {

    private val _selectedRoomId = MutableStateFlow<String?>(null)
    private val _statusFilter = MutableStateFlow(AssignmentStatusFilter.ALL)
    private val _searchQuery = MutableStateFlow("")
    private val _isRefreshing = MutableStateFlow(false)

    private val assignedRoomIds: List<String> =
        sessionStore?.profile?.scope?.roomIds?.filter { it.isNotBlank() } ?: emptyList()
    private val isTeacher: Boolean =
        sessionStore?.user?.role == Role.TEACHER
    private val isScoped: Boolean =
        isTeacher && assignedRoomIds.isNotEmpty()

    private val dataFlow = combine(
        assignmentsRepository.observeAll(),
        roomDao.observeAll(),
        assignmentStudentDao?.observeAll() ?: flowOf(emptyList<AssignmentStudentEntity>()),
        studentsRepository?.observeAll() ?: flowOf(emptyList<StudentEntity>()),
        submissionDao?.observeAll() ?: flowOf(emptyList<SubmissionEntity>())
    ) { assignments, allRooms, allLinks, allStudents, allSubmissions ->
        AssignmentData(assignments, allRooms, allLinks, allStudents, allSubmissions)
    }

    val uiState: StateFlow<AssignmentsUiState> = combine(
        dataFlow,
        _selectedRoomId,
        _statusFilter,
        _searchQuery,
        _isRefreshing
    ) { data, selectedRoomId, statusFilter, query, isRefreshing ->
        val assignments = data.assignments
        val allRooms = data.rooms
        val allLinks = data.links
        val allStudents = data.students
        val allSubmissions = data.submissions

        // Filter visible rooms based on scope
        val visibleRooms = if (isScoped) {
            allRooms.filter { it.id in assignedRoomIds }
        } else {
            allRooms
        }

        val roomMap = allRooms.associateBy { it.id }
        val studentMap = allStudents.associateBy { it.id }
        val linksByAssignment = allLinks.groupBy { it.assignmentId }
        val subsByAssignment = allSubmissions.groupBy { it.assignmentId }

        val now = LocalDate.now()
        val sixtyDaysAgo = now.minusDays(60)
        val sixtyDaysAhead = now.plusDays(60)

        // Map all assignments with their rooms and stats
        val mappedItems = assignments.filter {
            it.dueDate == null || (!it.dueDate.isBefore(sixtyDaysAgo) && !it.dueDate.isAfter(sixtyDaysAhead))
        }.map { assignment ->
            val links = linksByAssignment[assignment.id] ?: emptyList()
            val linkedStudents = links.mapNotNull { studentMap[it.studentId] }
            val assignedCount = linkedStudents.size
            val submissions = subsByAssignment[assignment.id] ?: emptyList()
            val submittedStudentIds = submissions.map { it.studentId }.distinct()
            val submittedCount = if (linkedStudents.isNotEmpty()) {
                val linkedIds = linkedStudents.map { it.id }.toSet()
                submittedStudentIds.count { it in linkedIds }
            } else {
                submittedStudentIds.size
            }

            // Determine room
            val primaryRoomId = linkedStudents.mapNotNull { it.roomId }.firstOrNull()
            val roomName = if (primaryRoomId != null) {
                roomMap[primaryRoomId]?.name ?: "قاعة عامة"
            } else {
                assignment.subject ?: "قاعة عامة"
            }

            val isCompleted = assignedCount > 0 && submittedCount >= assignedCount
            val isPastDue = !isCompleted && assignment.dueDate.isBefore(now)

            AssignmentItem(
                assignment = assignment,
                roomName = roomName,
                roomId = primaryRoomId,
                assignedCount = assignedCount,
                submittedCount = submittedCount,
                isCompleted = isCompleted,
                isPastDue = isPastDue
            )
        }

        // Scope filter for teacher
        val scopedItems = if (isScoped) {
            mappedItems.filter { item ->
                item.roomId in assignedRoomIds ||
                    (item.assignment.teacherUserId != null && item.assignment.teacherUserId == sessionStore?.user?.id) ||
                    (item.roomId == null && item.assignedCount == 0)
            }
        } else {
            mappedItems
        }

        // Apply Room selection
        val effectiveRoomId = selectedRoomId ?: visibleRooms.singleOrNull()?.takeIf { isScoped }?.id
        val roomFiltered = if (effectiveRoomId != null) {
            scopedItems.filter { it.roomId == effectiveRoomId }
        } else {
            scopedItems
        }

        // Apply Status Filter
        val statusFiltered = when (statusFilter) {
            AssignmentStatusFilter.ALL -> roomFiltered
            AssignmentStatusFilter.ACTIVE -> roomFiltered.filter { !it.isCompleted && !it.isPastDue }
            AssignmentStatusFilter.COMPLETED -> roomFiltered.filter { it.isCompleted }
            AssignmentStatusFilter.PAST_DUE -> roomFiltered.filter { it.isPastDue }
        }

        // Apply Search Query
        val searchFiltered = if (query.isBlank()) {
            statusFiltered
        } else {
            val q = query.trim()
            statusFiltered.filter {
                it.assignment.title.contains(q, ignoreCase = true) ||
                    (it.assignment.subject?.contains(q, ignoreCase = true) == true) ||
                    it.roomName.contains(q, ignoreCase = true)
            }
        }.sortedByDescending { it.assignment.dueDate }

        AssignmentsUiState(
            assignments = searchFiltered,
            rooms = visibleRooms,
            selectedRoomId = effectiveRoomId,
            statusFilter = statusFilter,
            searchQuery = query,
            isRefreshing = isRefreshing,
            isLoading = false,
            isScopedToAssigned = isScoped,
            totalCount = scopedItems.size,
            activeCount = scopedItems.count { !it.isCompleted && !it.isPastDue },
            completedCount = scopedItems.count { it.isCompleted }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AssignmentsUiState(isLoading = true))

    fun selectRoom(roomId: String?) {
        _selectedRoomId.value = roomId
    }

    fun setStatusFilter(filter: AssignmentStatusFilter) {
        _statusFilter.value = filter
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
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
