package sa.gheras.edutrack.ui.teacher

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import sa.gheras.edutrack.data.dao.RoomDao
import sa.gheras.edutrack.data.entity.RoomEntity
import sa.gheras.edutrack.data.entity.StudentEntity
import sa.gheras.edutrack.data.repo.StudentsRepository

data class StudentWithRoom(
    val student: StudentEntity,
    val roomName: String
)

data class StudentsUiState(
    val searchQuery: String = "",
    val selectedRoomId: String? = null,
    val rooms: List<RoomEntity> = emptyList(),
    val students: List<StudentWithRoom> = emptyList(),
    val isLoading: Boolean = false
)

class StudentsViewModel(
    private val studentsRepository: StudentsRepository,
    private val roomDao: RoomDao,
    initialRoomId: String? = null
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedRoomId = MutableStateFlow(initialRoomId)
    val selectedRoomId: StateFlow<String?> = _selectedRoomId.asStateFlow()

    val uiState: StateFlow<StudentsUiState> = combine(
        studentsRepository.observeAll(),
        roomDao.observeAll(),
        _searchQuery,
        _selectedRoomId
    ) { allStudents, rooms, query, roomId ->
        val roomMap = rooms.associateBy { it.id }

        val filtered = allStudents.filter { student ->
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
            isLoading = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StudentsUiState(isLoading = true))

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    fun selectRoom(roomId: String?) {
        _selectedRoomId.value = if (_selectedRoomId.value == roomId) null else roomId
    }
}
