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
import sa.gheras.edutrack.data.repo.AttendanceRecord
import sa.gheras.edutrack.data.repo.AttendanceRepository
import sa.gheras.edutrack.data.repo.StudentsRepository
import sa.gheras.edutrack.data.repo.mappers.DateParsers
import java.time.LocalDate

data class StudentAttendanceDraft(
    val student: StudentEntity,
    val status: String? = null,
    val note: String? = null,
    val isDraft: Boolean = true,
    val excuseId: String? = null,
    val isExcusedApproved: Boolean = false
)

data class AttendanceSheetUiState(
    val roomName: String = "",
    val date: LocalDate = LocalDate.now(),
    val drafts: List<StudentAttendanceDraft> = emptyList(),
    val isSaving: Boolean = false,
    val isApproving: Boolean = false,
    val isDirty: Boolean = false,
    val isAlreadyApproved: Boolean = false
) {
    val canApprove: Boolean
        get() = drafts.isNotEmpty() && drafts.all { it.status != null }

    val canSaveDraft: Boolean
        get() = drafts.any { it.status != null } && (isDirty || drafts.any { it.isDraft })
}

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

            val hasApprovedRecords = existingAttendance.values.any { !it.isDraft }

            val drafts = students.map { student ->
                val existing = existingAttendance[student.id]
                StudentAttendanceDraft(
                    student = student,
                    status = existing?.status, // P1 / G3: No default "حاضر"
                    note = existing?.note,
                    isDraft = existing?.isDraft ?: true,
                    excuseId = existing?.excuseId,
                    isExcusedApproved = existing?.status == "غياب بعذر"
                )
            }

            _uiState.update {
                it.copy(
                    roomName = room?.name ?: "القاعة",
                    drafts = drafts,
                    isDirty = false,
                    isAlreadyApproved = hasApprovedRecords
                )
            }
        }
    }

    fun updateStatus(studentId: String, status: String) {
        _uiState.update { state ->
            val updated = state.drafts.map { draft ->
                if (draft.student.id == studentId) {
                    draft.copy(status = status)
                } else draft
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

    fun saveDraft(onSaved: () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            val markedRecords = _uiState.value.drafts
                .filter { it.status != null }
                .map { draft ->
                    val safeStatus = if (draft.status == "غياب بعذر") "غائب" else draft.status!!
                    AttendanceRecord(
                        studentId = draft.student.id,
                        status = safeStatus,
                        note = draft.note
                    )
                }
            attendanceRepository.saveDraftAttendanceBatch(roomId, targetDate, markedRecords)
            _uiState.update { it.copy(isSaving = false, isDirty = false) }
            onSaved()
        }
    }

    fun approve(onApproved: () -> Unit) {
        if (!_uiState.value.canApprove) return
        viewModelScope.launch {
            _uiState.update { it.copy(isApproving = true) }
            val records = _uiState.value.drafts.map { draft ->
                val safeStatus = if (draft.status == "غياب بعذر") "غائب" else draft.status!!
                AttendanceRecord(
                    studentId = draft.student.id,
                    status = safeStatus,
                    note = draft.note
                )
            }
            attendanceRepository.saveDraftAttendanceBatch(roomId, targetDate, records)
            attendanceRepository.approveRoomAttendance(roomId, targetDate)
            _uiState.update { it.copy(isApproving = false, isDirty = false, isAlreadyApproved = true) }
            onApproved()
        }
    }

    fun save(onSaved: () -> Unit) {
        saveDraft(onSaved)
    }
}
