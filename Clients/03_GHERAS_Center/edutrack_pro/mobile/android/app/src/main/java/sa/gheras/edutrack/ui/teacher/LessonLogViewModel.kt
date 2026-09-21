package sa.gheras.edutrack.ui.teacher

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import sa.gheras.edutrack.data.dao.RoomDao
import sa.gheras.edutrack.data.entity.ScheduleEntity
import sa.gheras.edutrack.data.repo.LessonLogsRepository
import sa.gheras.edutrack.data.repo.ScheduleRepository
import sa.gheras.edutrack.data.repo.mappers.DateParsers
import java.time.LocalDate

data class LessonLogUiState(
    val schedule: ScheduleEntity? = null,
    val roomName: String = "",
    val date: LocalDate = LocalDate.now(),
    val status: String = "تمت",
    val covered: String = "",
    val homework: String = "",
    val notes: String = "",
    val isSaving: Boolean = false,
    val isDirty: Boolean = false
)

class LessonLogViewModel(
    val scheduleId: String,
    val dateStr: String,
    private val scheduleRepository: ScheduleRepository,
    private val roomDao: RoomDao,
    private val lessonLogsRepository: LessonLogsRepository
) : ViewModel() {

    private val targetDate: LocalDate = DateParsers.parseLocalDate(dateStr) ?: LocalDate.now()

    private val _uiState = MutableStateFlow(LessonLogUiState(date = targetDate))
    val uiState: StateFlow<LessonLogUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            val schedule = scheduleRepository.getById(scheduleId)
            val room = schedule?.roomId?.let { roomDao.getById(it) }
            val existing = lessonLogsRepository.getByScheduleAndDate(scheduleId, targetDate)

            _uiState.update {
                it.copy(
                    schedule = schedule,
                    roomName = room?.name ?: schedule?.groupName ?: "الحلقة",
                    status = existing?.status ?: "تمت",
                    covered = existing?.covered ?: "",
                    homework = existing?.homework ?: "",
                    notes = existing?.notes ?: "",
                    isDirty = false
                )
            }
        }
    }

    fun updateStatus(status: String) = _uiState.update { it.copy(status = status, isDirty = true) }
    fun updateCovered(value: String) = _uiState.update { it.copy(covered = value, isDirty = true) }
    fun updateHomework(value: String) = _uiState.update { it.copy(homework = value, isDirty = true) }
    fun updateNotes(value: String) = _uiState.update { it.copy(notes = value, isDirty = true) }

    fun save(onSaved: () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            val state = _uiState.value
            lessonLogsRepository.recordLessonLog(
                scheduleId = scheduleId,
                date = targetDate,
                status = state.status,
                covered = state.covered.ifBlank { null },
                homework = state.homework.ifBlank { null },
                notes = state.notes.ifBlank { null }
            )
            _uiState.update { it.copy(isSaving = false, isDirty = false) }
            onSaved()
        }
    }
}
