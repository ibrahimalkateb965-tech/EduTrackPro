package sa.gheras.edutrack.ui.teacher

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import sa.gheras.edutrack.data.dao.RoomDao
import sa.gheras.edutrack.data.entity.StudentEntity
import sa.gheras.edutrack.data.repo.AttendanceRecord
import sa.gheras.edutrack.data.repo.AttendanceRepository
import sa.gheras.edutrack.data.repo.StudentsRepository
import sa.gheras.edutrack.data.repo.mappers.DateParsers
import java.time.LocalDate

data class StudentAttendanceDraft(
    val student: StudentEntity,
    val status: String = "حاضر",
    val note: String? = null
)

data class AttendanceSheetUiState(
    val roomName: String = "",
    val date: LocalDate = LocalDate.now(),
    val drafts: List<StudentAttendanceDraft> = emptyList(),
    val isSaving: Boolean = false,
    val isDirty: Boolean = false
)

class AttendanceSheetViewModel(
    val roomId: String,
    val dateStr: String,
    private val studentsRepository: StudentsRepository,
    private val roomDao: RoomDao,
    private val attendanceRepository: AttendanceRepository
) : ViewModel() {

    private val targetDate: LocalDate = DateParsers.parseLocalDate(dateStr) ?: LocalDate.now()

    private val _uiState = MutableStateFlow(AttendanceSheetUiState(date = targetDate))
    val uiState: StateFlow<AttendanceSheetUiState> = _uiState.asStateFlow()

    init {
        loadStudentsAndExisting()
    }

    private fun loadStudentsAndExisting() {
        viewModelScope.launch {
            val room = roomDao.getById(roomId)
            val students = studentsRepository.observeByRoom(roomId).first()
            val existingAttendance = attendanceRepository.observeByDate(targetDate).first()
                .associateBy { it.studentId }

            val drafts = students.map { student ->
                val existing = existingAttendance[student.id]
                StudentAttendanceDraft(
                    student = student,
                    status = existing?.status ?: "حاضر",
                    note = existing?.note
                )
            }

            _uiState.update {
                it.copy(
                    roomName = room?.name ?: "القاعة",
                    drafts = drafts,
                    isDirty = false
                )
            }
        }
    }

    fun updateStatus(studentId: String, status: String) {
        _uiState.update { state ->
            val updated = state.drafts.map { draft ->
                if (draft.student.id == studentId) draft.copy(status = status) else draft
            }
            state.copy(drafts = updated, isDirty = true)
        }
    }

    fun updateNote(studentId: String, note: String?) {
        _uiState.update { state ->
            val updated = state.drafts.map { draft ->
                if (draft.student.id == studentId) draft.copy(note = note) else draft
            }
            state.copy(drafts = updated, isDirty = true)
        }
    }

    fun save(onSaved: () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            val records = _uiState.value.drafts.map { draft ->
                AttendanceRecord(
                    studentId = draft.student.id,
                    status = draft.status,
                    note = draft.note
                )
            }
            attendanceRepository.recordAttendanceBatch(targetDate, records)
            _uiState.update { it.copy(isSaving = false, isDirty = false) }
            onSaved()
        }
    }
}
