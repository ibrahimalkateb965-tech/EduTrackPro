package sa.gheras.edutrack.ui.guardian.threads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import sa.gheras.edutrack.data.entity.StudentEntity
import sa.gheras.edutrack.data.entity.ThreadEntity
import sa.gheras.edutrack.data.remote.dto.CreateThreadRequest
import sa.gheras.edutrack.data.repo.StudentsRepository
import sa.gheras.edutrack.data.repo.ThreadsRepository

data class GuardianThreadsUiState(
    val threads: List<ThreadEntity> = emptyList(),
    val students: List<StudentEntity> = emptyList(),
    val selectedStudentId: String? = null,
    val isLoading: Boolean = false,
    val isCreatingThread: Boolean = false,
    val errorMessage: String? = null
)

class GuardianThreadsViewModel(
    private val threadsRepository: ThreadsRepository,
    private val studentsRepository: StudentsRepository
) : ViewModel() {

    private val _selectedStudentId = MutableStateFlow<String?>(null)
    private val _isLoading = MutableStateFlow(false)
    private val _isCreating = MutableStateFlow(false)
    private val _errorMessage = MutableStateFlow<String?>(null)

    private val _opStatus = combine(_isLoading, _isCreating, _errorMessage) { loading, creating, error ->
        Triple(loading, creating, error)
    }

    val uiState: StateFlow<GuardianThreadsUiState> = combine(
        threadsRepository.observeThreads(),
        studentsRepository.observeAll(),
        _selectedStudentId,
        _opStatus
    ) { threads, students, selectedId, (loading, creating, error) ->
        val filtered = if (selectedId != null) {
            threads.filter { it.studentId == selectedId }
        } else {
            threads
        }
        GuardianThreadsUiState(
            threads = filtered,
            students = students,
            selectedStudentId = selectedId,
            isLoading = loading,
            isCreatingThread = creating,
            errorMessage = error
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GuardianThreadsUiState())

    init {
        refresh()
    }

    fun selectStudent(studentId: String?) {
        _selectedStudentId.value = studentId
    }

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            threadsRepository.refreshThreads(_selectedStudentId.value)
                .onFailure { _errorMessage.value = it.message ?: "تعذر تحديث المحادثات" }
            _isLoading.value = false
        }
    }

    fun createThread(studentId: String, subject: String, firstMessage: String, onSuccess: (String) -> Unit) {
        viewModelScope.launch {
            _isCreating.value = true
            _errorMessage.value = null
            val req = CreateThreadRequest(
                studentId = studentId,
                subject = subject,
                firstMessage = firstMessage
            )
            threadsRepository.createThread(req)
                .onSuccess { thread ->
                    _isCreating.value = false
                    onSuccess(thread.id)
                }
                .onFailure {
                    _isCreating.value = false
                    _errorMessage.value = it.message ?: "تعذر إنشاء المحادثة"
                }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }
}
