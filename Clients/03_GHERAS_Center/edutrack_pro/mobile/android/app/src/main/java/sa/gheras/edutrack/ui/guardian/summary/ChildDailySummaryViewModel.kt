package sa.gheras.edutrack.ui.guardian.summary

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
import sa.gheras.edutrack.data.remote.dto.CreateThreadRequest
import sa.gheras.edutrack.data.repo.DailySummariesRepository
import sa.gheras.edutrack.data.repo.StudentsRepository
import sa.gheras.edutrack.data.repo.ThreadsRepository
import java.time.LocalDate

data class ChildDailySummaryUiState(
    val student: StudentEntity? = null,
    val selectedDate: LocalDate = LocalDate.now(),
    val summaries: List<DailySummaryEntity> = emptyList(),
    val studentNotes: Map<String, String> = emptyMap(),
    val isLoading: Boolean = false,
    val isInquiring: Boolean = false,
    val errorMessage: String? = null
)

class ChildDailySummaryViewModel(
    val studentId: String,
    initialDateStr: String? = null,
    private val dailySummariesRepository: DailySummariesRepository,
    private val studentsRepository: StudentsRepository,
    private val threadsRepository: ThreadsRepository
) : ViewModel() {

    private val _selectedDate = MutableStateFlow(
        initialDateStr?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()
    )
    private val _summaries = MutableStateFlow<List<DailySummaryEntity>>(emptyList())
    private val _studentNotes = MutableStateFlow<Map<String, String>>(emptyMap())
    private val _isLoading = MutableStateFlow(false)
    private val _isInquiring = MutableStateFlow(false)
    private val _errorMessage = MutableStateFlow<String?>(null)

    private val _dataState = combine(_selectedDate, _summaries, _studentNotes) { date, summaries, notes ->
        Triple(date, summaries, notes)
    }

    private val _opStatus = combine(_isLoading, _isInquiring, _errorMessage) { loading, inquiring, error ->
        Triple(loading, inquiring, error)
    }

    val uiState: StateFlow<ChildDailySummaryUiState> = combine(
        studentsRepository.observeById(studentId),
        _dataState,
        _opStatus
    ) { student, (date, summaries, notes), (loading, inquiring, error) ->
        ChildDailySummaryUiState(
            student = student,
            selectedDate = date,
            summaries = summaries.filter { it.isPublished },
            studentNotes = notes,
            isLoading = loading,
            isInquiring = inquiring,
            errorMessage = error
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChildDailySummaryUiState(isLoading = true))

    init {
        refresh()
    }

    fun selectDate(date: LocalDate) {
        _selectedDate.value = date
        refresh()
    }

    fun nextDay() {
        _selectedDate.value = _selectedDate.value.plusDays(1)
        refresh()
    }

    fun previousDay() {
        _selectedDate.value = _selectedDate.value.minusDays(1)
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            val dateStr = _selectedDate.value.toString()
            dailySummariesRepository.refreshGuardianSummaries(studentId, dateStr)
                .onSuccess { dtoList ->
                    val summaries = dtoList.map { dto ->
                        DailySummaryEntity(
                            id = dto.id,
                            branchId = dto.branchId,
                            roomId = dto.roomId,
                            teacherUserId = dto.teacherUserId,
                            summaryDate = LocalDate.parse(dto.summaryDate),
                            subject = dto.subject,
                            title = dto.title,
                            classSummary = dto.classSummary,
                            homeworkSummary = dto.homeworkSummary,
                            isPublished = dto.isPublished,
                            publishedAt = dto.publishedAt?.let { java.time.Instant.parse(it) },
                            createdAt = dto.createdAt?.let { java.time.Instant.parse(it) } ?: java.time.Instant.now(),
                            updatedAt = dto.updatedAt?.let { java.time.Instant.parse(it) } ?: java.time.Instant.now()
                        )
                    }
                    val notesMap = mutableMapOf<String, String>()
                    dtoList.forEach { dto ->
                        dto.studentPrivateNote?.let { note ->
                            notesMap[dto.id] = note
                        }
                    }
                    _summaries.value = summaries
                    _studentNotes.value = notesMap
                }
                .onFailure {
                    _errorMessage.value = it.message ?: "تعذر جلب الملخصات اليومية"
                }
            _isLoading.value = false
        }
    }

    fun inquireAboutSummary(
        summaryId: String,
        subject: String,
        title: String,
        onSuccess: (String) -> Unit
    ) {
        viewModelScope.launch {
            _isInquiring.value = true
            _errorMessage.value = null
            val request = CreateThreadRequest(
                studentId = studentId,
                subject = "استفسار: $subject - $title",
                firstMessage = "السلام عليكم ورحمة الله، أود الاستفسار بخصوص ملخص درس ($title) لمادة ($subject).",
                linkedSummaryId = summaryId
            )
            threadsRepository.createThread(request)
                .onSuccess {
                    _isInquiring.value = false
                    onSuccess(it.id)
                }
                .onFailure {
                    _isInquiring.value = false
                    _errorMessage.value = it.message ?: "تعذر بدء المحادثة مع المعلمة"
                }
        }
    }
}
