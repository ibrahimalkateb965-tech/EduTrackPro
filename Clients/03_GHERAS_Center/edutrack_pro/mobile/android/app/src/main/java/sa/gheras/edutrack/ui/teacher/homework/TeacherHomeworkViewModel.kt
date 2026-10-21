package sa.gheras.edutrack.ui.teacher.homework

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import sa.gheras.edutrack.data.dao.RoomDao
import sa.gheras.edutrack.data.entity.RoomEntity
import sa.gheras.edutrack.data.entity.StudentEntity
import sa.gheras.edutrack.data.local.session.SessionStore
import sa.gheras.edutrack.data.repo.AssignmentsRepository
import sa.gheras.edutrack.data.repo.StudentsRepository
import java.time.LocalDate

data class CreateHomeworkUiState(
    val title: String = "",
    val subject: String = "القرآن الكريم",
    val dueDate: LocalDate? = null,
    val instructions: String = "",
    val pageRef: String = "",
    val selectedRoomId: String? = null,
    val rooms: List<RoomEntity> = emptyList(),
    val students: List<StudentEntity> = emptyList(),
    val selectedStudentIds: Set<String> = emptySet(),
    val attachments: List<Uri> = emptyList(),
    val isDraft: Boolean = false,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val savedAssignmentId: String? = null,
    val errorMessage: String? = null,
    val isSuccess: Boolean = false
)

class TeacherHomeworkViewModel(
    private val assignmentsRepository: AssignmentsRepository,
    private val studentsRepository: StudentsRepository,
    private val roomDao: RoomDao,
    private val sessionStore: SessionStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(CreateHomeworkUiState())
    val uiState: StateFlow<CreateHomeworkUiState> = _uiState.asStateFlow()

    init {
        loadInitialData()
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            try {
                val rooms = roomDao.getAll()
                val students = studentsRepository.getAll()
                _uiState.update {
                    it.copy(
                        rooms = rooms,
                        students = students,
                        selectedStudentIds = students.map { s -> s.id }.toSet(),
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message) }
            }
        }
    }

    fun updateTitle(value: String) = _uiState.update { it.copy(title = value) }
    fun updateSubject(value: String) = _uiState.update { it.copy(subject = value) }
    fun updateDueDate(date: LocalDate?) = _uiState.update { it.copy(dueDate = date) }
    fun updateInstructions(value: String) = _uiState.update { it.copy(instructions = value) }
    fun updatePageRef(value: String) = _uiState.update { it.copy(pageRef = value) }

    fun selectRoom(roomId: String?) {
        _uiState.update { state ->
            val roomStudents = if (roomId != null) {
                state.students.filter { it.roomId == roomId }.map { it.id }.toSet()
            } else {
                state.students.map { it.id }.toSet()
            }
            state.copy(selectedRoomId = roomId, selectedStudentIds = roomStudents)
        }
    }

    fun toggleStudent(studentId: String) {
        _uiState.update { state ->
            val updated = if (state.selectedStudentIds.contains(studentId)) {
                state.selectedStudentIds - studentId
            } else {
                state.selectedStudentIds + studentId
            }
            state.copy(selectedStudentIds = updated)
        }
    }

    fun selectAllStudents() {
        _uiState.update { state ->
            state.copy(selectedStudentIds = state.students.map { it.id }.toSet())
        }
    }

    fun addAttachment(uri: Uri) {
        _uiState.update { state ->
            if (state.attachments.size < 10 && !state.attachments.contains(uri)) {
                state.copy(attachments = state.attachments + uri)
            } else state
        }
    }

    fun removeAttachment(uri: Uri) {
        _uiState.update { it.copy(attachments = it.attachments - uri) }
    }

    fun saveAsDraft(onDone: (String) -> Unit) {
        saveAssignment(isDraft = true, onDone = onDone)
    }

    fun publishNow(onDone: (String) -> Unit) {
        saveAssignment(isDraft = false, onDone = onDone)
    }

    private fun saveAssignment(isDraft: Boolean, onDone: (String) -> Unit) {
        val state = _uiState.value
        if (state.title.isBlank()) {
            _uiState.update { it.copy(errorMessage = "يرجى كتابة عنوان للواجب") }
            return
        }
        if (state.selectedStudentIds.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "يرجى اختيار طالب واحد على الأقل") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            try {
                val teacherUserId = sessionStore.user?.id
                val created = assignmentsRepository.createAssignment(
                    title = state.title.trim(),
                    subject = state.subject.trim(),
                    dueDate = state.dueDate,
                    instructions = state.instructions.trim().takeIf { it.isNotBlank() },
                    pageRef = state.pageRef.trim().takeIf { it.isNotBlank() },
                    teacherUserId = teacherUserId,
                    studentIds = state.selectedStudentIds.toList(),
                    isDraft = isDraft
                )

                if (!isDraft) {
                    assignmentsRepository.publishAssignment(created.id)
                }

                _uiState.update { it.copy(isSaving = false, savedAssignmentId = created.id, isSuccess = true) }
                onDone(created.id)
            } catch (e: Exception) {
                _uiState.update { it.copy(isSaving = false, errorMessage = e.message ?: "حدث خطأ أثناء حفظ الواجب") }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
