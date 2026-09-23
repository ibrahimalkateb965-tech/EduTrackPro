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
import sa.gheras.edutrack.data.dao.RoomDao
import sa.gheras.edutrack.data.entity.RoomEntity
import sa.gheras.edutrack.data.entity.StudentEntity
import sa.gheras.edutrack.data.local.session.Role
import sa.gheras.edutrack.data.local.session.SessionStore
import sa.gheras.edutrack.data.repo.StudentsRepository
import sa.gheras.edutrack.sync.PullSync

data class StudentWithRoom(
    val student: StudentEntity,
    val roomName: String
)

data class StudentsUiState(
    val searchQuery: String = "",
    val selectedRoomId: String? = null,
    val rooms: List<RoomEntity> = emptyList(),
    val students: List<StudentWithRoom> = emptyList(),
    /** True when the list is restricted to the teacher's assigned circles (profile.scope.roomIds). */
    val isScopedToAssigned: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoading: Boolean = false
)

/** Rooms/students visible to the current user after applying the teacher's assigned-circle scope. */
internal data class ScopedRoster(
    val rooms: List<RoomEntity>,
    val students: List<StudentEntity>,
    val isScoped: Boolean
)

class StudentsViewModel(
    private val studentsRepository: StudentsRepository,
    private val roomDao: RoomDao,
    private val sessionStore: SessionStore,
    private val pullSync: PullSync? = null,
    initialRoomId: String? = null
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedRoomId = MutableStateFlow(initialRoomId)
    val selectedRoomId: StateFlow<String?> = _selectedRoomId.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)

    val uiState: StateFlow<StudentsUiState> = combine(
        studentsRepository.observeAll(),
        roomDao.observeAll(),
        _searchQuery,
        _selectedRoomId,
        _isRefreshing
    ) { allStudents, allRooms, query, selectedId, refreshing ->
        val roster = scopeRoster(
            role = sessionStore.user?.role,
            assignedRoomIds = sessionStore.profile?.scope?.roomIds.orEmpty(),
            rooms = allRooms,
            students = allStudents
        )
        val rooms = roster.rooms
        val roomMap = allRooms.associateBy { it.id }
        // A teacher with a single circle always sees it selected.
        val roomId = selectedId ?: rooms.singleOrNull()?.takeIf { roster.isScoped }?.id

        val filtered = roster.students.filter { student ->
            val matchesRoom = roomId == null || student.roomId == roomId
            val matchesQuery = query.isBlank() || student.name.contains(query.trim(), ignoreCase = true)
            matchesRoom && matchesQuery
        }.map { student ->
            StudentWithRoom(
                student = student,
                roomName = roomMap[student.roomId]?.name ?: "حلقة غير محددة"
            )
        }

        StudentsUiState(
            searchQuery = query,
            selectedRoomId = roomId,
            rooms = rooms,
            students = filtered,
            isScopedToAssigned = roster.isScoped,
            isRefreshing = refreshing,
            isLoading = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StudentsUiState(isLoading = true))

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    fun selectRoom(roomId: String?) {
        _selectedRoomId.value = if (_selectedRoomId.value == roomId) null else roomId
    }

    fun refresh() {
        val sync = pullSync ?: return
        if (_isRefreshing.value) return
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                sync.requestFull()
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    companion object {
        internal fun scopeRoster(
            role: Role?,
            assignedRoomIds: List<String>,
            rooms: List<RoomEntity>,
            students: List<StudentEntity>
        ): ScopedRoster {
            if (role != Role.TEACHER || assignedRoomIds.isEmpty()) {
                return ScopedRoster(rooms, students, isScoped = false)
            }
            val assigned = assignedRoomIds.toSet()
            return ScopedRoster(
                rooms = rooms.filter { it.id in assigned },
                students = students.filter { it.roomId in assigned },
                isScoped = true
            )
        }
    }
}
