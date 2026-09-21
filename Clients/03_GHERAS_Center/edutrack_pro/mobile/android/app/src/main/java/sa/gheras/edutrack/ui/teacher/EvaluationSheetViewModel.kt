package sa.gheras.edutrack.ui.teacher

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import sa.gheras.edutrack.data.dao.RoomDao
import sa.gheras.edutrack.data.entity.StudentEntity
import sa.gheras.edutrack.data.repo.EvaluationRecord
import sa.gheras.edutrack.data.repo.EvaluationsRepository
import sa.gheras.edutrack.data.repo.ScheduleRepository
import sa.gheras.edutrack.data.repo.StudentsRepository
import sa.gheras.edutrack.data.repo.mappers.DateParsers
import java.time.LocalDate

data class StudentEvalDraft(
    val student: StudentEntity,
    val score: Double = 10.0,
    val notes: String? = null
)

data class EvaluationSheetUiState(
    val roomName: String = "",
    val date: LocalDate = LocalDate.now(),
    val availableSubjects: List<String> = listOf("القرآن", "لغتي", "الإنجليزي", "الرياضيات"),
    val selectedSubject: String = "القرآن",
    val drafts: List<StudentEvalDraft> = emptyList(),
    val isSaving: Boolean = false,
    val isDirty: Boolean = false
)

class EvaluationSheetViewModel(
    val roomId: String,
    val dateStr: String,
    initialSubject: String = "",
    private val studentsRepository: StudentsRepository,
    private val roomDao: RoomDao,
    private val scheduleRepository: ScheduleRepository,
    private val evaluationsRepository: EvaluationsRepository
) : ViewModel() {

    private val targetDate: LocalDate = DateParsers.parseLocalDate(dateStr) ?: LocalDate.now()

    private val _uiState = MutableStateFlow(
        EvaluationSheetUiState(
            date = targetDate,
            selectedSubject = if (initialSubject.isNotBlank()) initialSubject else "القرآن"
        )
    )
    val uiState: StateFlow<EvaluationSheetUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            val room = roomDao.getById(roomId)
            val students = studentsRepository.observeByRoom(roomId).first()
            val roomSchedules = scheduleRepository.observeByRoom(roomId).first()
            val subjects = roomSchedules.map { it.subject }.distinct().ifEmpty {
                listOf("القرآن", "لغتي", "الإنجليزي", "الرياضيات")
            }

            val currentSubject = if (_uiState.value.selectedSubject in subjects) {
                _uiState.value.selectedSubject
            } else {
                subjects.first()
            }

            val existingEvals = evaluationsRepository.observeByDate(targetDate).first()
                .filter { it.subject == currentSubject }
                .associateBy { it.studentId }

            val drafts = students.map { student ->
                val existing = existingEvals[student.id]
                StudentEvalDraft(
                    student = student,
                    score = existing?.value ?: 10.0,
                    notes = null
                )
            }

            _uiState.update {
                it.copy(
                    roomName = room?.name ?: "الحلقة",
                    availableSubjects = subjects,
                    selectedSubject = currentSubject,
                    drafts = drafts,
                    isDirty = false
                )
            }
        }
    }

    fun selectSubject(subject: String) {
        if (_uiState.value.selectedSubject != subject) {
            _uiState.update { it.copy(selectedSubject = subject) }
            loadData()
        }
    }

    fun updateScore(studentId: String, delta: Double) {
        _uiState.update { state ->
            val updated = state.drafts.map { draft ->
                if (draft.student.id == studentId) {
                    val newScore = (draft.score + delta).coerceIn(0.0, 10.0)
                    draft.copy(score = newScore)
                } else draft
            }
            state.copy(drafts = updated, isDirty = true)
        }
    }

    fun setScore(studentId: String, score: Double) {
        _uiState.update { state ->
            val updated = state.drafts.map { draft ->
                if (draft.student.id == studentId) {
                    draft.copy(score = score.coerceIn(0.0, 10.0))
                } else draft
            }
            state.copy(drafts = updated, isDirty = true)
        }
    }

    fun save(onSaved: () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            val records = _uiState.value.drafts.map { draft ->
                EvaluationRecord(
                    studentId = draft.student.id,
                    score = draft.score,
                    notes = draft.notes
                )
            }
            evaluationsRepository.recordEvaluationBatch(
                date = targetDate,
                subject = _uiState.value.selectedSubject,
                records = records
            )
            _uiState.update { it.copy(isSaving = false, isDirty = false) }
            onSaved()
        }
    }
}
