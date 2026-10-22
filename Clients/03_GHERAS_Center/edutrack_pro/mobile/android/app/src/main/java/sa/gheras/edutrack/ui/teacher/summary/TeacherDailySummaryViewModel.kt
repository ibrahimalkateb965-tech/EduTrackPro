package sa.gheras.edutrack.ui.teacher.summary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import sa.gheras.edutrack.data.entity.DailySummaryEntity
import sa.gheras.edutrack.data.entity.StudentEntity
import sa.gheras.edutrack.data.remote.dto.StudentNoteDto
import sa.gheras.edutrack.data.remote.dto.UpsertDailySummaryRequest
import sa.gheras.edutrack.data.repo.DailySummariesRepository
import sa.gheras.edutrack.data.repo.StudentsRepository
import java.time.LocalDate

data class TeacherDailySummaryUiState(
    val selectedDate: LocalDate = LocalDate.now(),
    val summary: DailySummaryEntity? = null,
    val students: List<StudentEntity> = emptyList(),
    val subject: String = "",
    val title: String = "",
    val classSummary: String = "",
    val homeworkSummary: String = "",
    val studentNotes: Map<String, String> = emptyMap(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val successMessage: String? = null,
    val errorMessage: String? = null
)

class TeacherDailySummaryViewModel(
    val roomId: String,
    private val dailySummariesRepository: DailySummariesRepository,
    private val studentsRepository: StudentsRepository
) : ViewModel() {

    private val _selectedDate = MutableStateFlow(LocalDate.now())
    private val _subject = MutableStateFlow("")
    private val _title = MutableStateFlow("")
    private val _classSummary = MutableStateFlow("")
    private val _homeworkSummary = MutableStateFlow("")
    private val _studentNotes = MutableStateFlow<Map<String, String>>(emptyMap())
    private val _isLoading = MutableStateFlow(false)
    private val _isSaving = MutableStateFlow(false)
    private val _successMessage = MutableStateFlow<String?>(null)
    private val _errorMessage = MutableStateFlow<String?>(null)

    private val _textFields = combine(_subject, _title, _classSummary) { subject, title, classSummary ->
        Triple(subject, title, classSummary)
    }

    val uiState: StateFlow<TeacherDailySummaryUiState> = combine(
        _selectedDate,
        dailySummariesRepository.observeSummariesByRoom(roomId),
        studentsRepository.observeByRoom(roomId),
        _textFields
    ) { date, summaries, students, (subject, title, classSummary) ->
        val activeSummary = summaries.find { it.summaryDate == date }
        TeacherDailySummaryUiState(
            selectedDate = date,
            summary = activeSummary,
            students = students,
            subject = if (subject.isNotBlank()) subject else activeSummary?.subject.orEmpty(),
            title = if (title.isNotBlank()) title else activeSummary?.title.orEmpty(),
            classSummary = if (classSummary.isNotBlank()) classSummary else activeSummary?.classSummary.orEmpty(),
            homeworkSummary = _homeworkSummary.value.ifBlank { activeSummary?.homeworkSummary.orEmpty() },
            studentNotes = _studentNotes.value,
            isLoading = _isLoading.value,
            isSaving = _isSaving.value,
            successMessage = _successMessage.value,
            errorMessage = _errorMessage.value
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TeacherDailySummaryUiState())

    init {
        refresh()
    }

    fun setDate(date: LocalDate) {
        _selectedDate.value = date
        _subject.value = ""
        _title.value = ""
        _classSummary.value = ""
        _homeworkSummary.value = ""
        refresh()
    }

    fun updateSubject(v: String) { _subject.value = v }
    fun updateTitle(v: String) { _title.value = v }
    fun updateClassSummary(v: String) { _classSummary.value = v }
    fun updateHomeworkSummary(v: String) { _homeworkSummary.value = v }

    fun updateStudentNote(studentId: String, note: String) {
        _studentNotes.value = _studentNotes.value.toMutableMap().apply {
            put(studentId, note)
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            dailySummariesRepository.refreshStaffSummaries(roomId, _selectedDate.value.toString())
                .onFailure { _errorMessage.value = it.message }
            _isLoading.value = false
        }
    }

    fun saveSummary(isPublished: Boolean) {
        val s = _subject.value.ifBlank { uiState.value.summary?.subject.orEmpty() }
        val t = _title.value.ifBlank { uiState.value.summary?.title.orEmpty() }
        val c = _classSummary.value.ifBlank { uiState.value.summary?.classSummary.orEmpty() }

        if (s.isBlank() || t.isBlank() || c.isBlank()) {
            _errorMessage.value = "يرجى تعبئة المادة، العنوان، وملخص الدرس"
            return
        }

        viewModelScope.launch {
            _isSaving.value = true
            _errorMessage.value = null

            val notesList = _studentNotes.value.map { (studentId, note) ->
                StudentNoteDto(studentId = studentId, privateNote = note)
            }

            val req = UpsertDailySummaryRequest(
                roomId = roomId,
                summaryDate = _selectedDate.value.toString(),
                subject = s.trim(),
                title = t.trim(),
                classSummary = c.trim(),
                homeworkSummary = _homeworkSummary.value.ifBlank { null },
                isPublished = isPublished,
                studentNotes = notesList
            )

            dailySummariesRepository.upsertDailySummary(req)
                .onSuccess {
                    _isSaving.value = false
                    _successMessage.value = if (isPublished) "تم نشر ملخص الدرس للأسر بنجاح" else "تم حفظ المسودة بنجاح"
                    refresh()
                }
                .onFailure {
                    _isSaving.value = false
                    _errorMessage.value = it.message ?: "تعذر حفظ ملخص الدرس"
                }
        }
    }

    fun clearMessages() {
        _successMessage.value = null
        _errorMessage.value = null
    }
}
