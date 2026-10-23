package sa.gheras.edutrack.ui.teacher.report

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import sa.gheras.edutrack.data.entity.MonthlyStudentReportEntity
import sa.gheras.edutrack.data.entity.StudentEntity
import sa.gheras.edutrack.data.remote.dto.AttendanceStatsDto
import sa.gheras.edutrack.data.remote.dto.SubjectRatingDto
import sa.gheras.edutrack.data.remote.dto.UpsertMonthlyReportRequest
import sa.gheras.edutrack.data.repo.MonthlyReportsRepository
import sa.gheras.edutrack.data.repo.StudentsRepository
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class TeacherMonthlyReportUiState(
    val selectedMonth: String = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM")),
    val students: List<StudentEntity> = emptyList(),
    val selectedStudentId: String? = null,
    val currentReport: MonthlyStudentReportEntity? = null,
    val attendanceStats: AttendanceStatsDto? = null,
    val subjectRatings: Map<String, Pair<String, String>> = emptyMap(), // subject -> (rating, notes)
    val strengths: String = "",
    val improvements: String = "",
    val recommendations: String = "",
    val revisionNotes: String? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val successMessage: String? = null,
    val errorMessage: String? = null
)

class TeacherMonthlyReportViewModel(
    val roomId: String,
    private val monthlyReportsRepository: MonthlyReportsRepository,
    private val studentsRepository: StudentsRepository
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }
    private val _uiState = MutableStateFlow(TeacherMonthlyReportUiState())
    val uiState: StateFlow<TeacherMonthlyReportUiState> = _uiState.asStateFlow()

    private val defaultSubjects = listOf("القرآن", "لغتي", "الإنجليزي", "الرياضيات")

    init {
        loadStudents()
    }

    private fun loadStudents() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val studentList = studentsRepository.observeByRoom(roomId).firstOrNull() ?: emptyList()
            val firstStudentId = studentList.firstOrNull()?.id
            _uiState.update {
                it.copy(
                    students = studentList,
                    selectedStudentId = firstStudentId,
                    isLoading = false
                )
            }
            if (firstStudentId != null) {
                loadReportForStudent(firstStudentId, _uiState.value.selectedMonth)
            }
        }
    }

    fun selectStudent(studentId: String) {
        _uiState.update { it.copy(selectedStudentId = studentId) }
        loadReportForStudent(studentId, _uiState.value.selectedMonth)
    }

    fun selectMonth(month: String) {
        _uiState.update { it.copy(selectedMonth = month) }
        val currentStudentId = _uiState.value.selectedStudentId
        if (currentStudentId != null) {
            loadReportForStudent(currentStudentId, month)
        }
    }

    fun loadReportForStudent(studentId: String, month: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            // 1. Fetch auto stats from server
            val statsResult = monthlyReportsRepository.fetchAutoStats(studentId, month)
            val stats = statsResult.getOrNull()

            // 2. Fetch or observe existing report from DB
            val existingReport = monthlyReportsRepository.observeReportByStudentAndMonth(studentId, month).firstOrNull()

            if (existingReport != null) {
                val ratingsMap = mutableMapOf<String, Pair<String, String>>()
                try {
                    val parsedRatings = json.decodeFromString<List<SubjectRatingDto>>(existingReport.subjectRatingsJson)
                    parsedRatings.forEach { r ->
                        ratingsMap[r.subject] = Pair(r.rating, r.notes ?: "")
                    }
                } catch (_: Exception) {}

                // ensure default subjects exist in map
                defaultSubjects.forEach { s ->
                    if (!ratingsMap.containsKey(s)) {
                        ratingsMap[s] = Pair("ممتاز", "")
                    }
                }

                val savedStats = try {
                    json.decodeFromString<AttendanceStatsDto>(existingReport.attendanceStatsJson)
                } catch (_: Exception) {
                    stats ?: AttendanceStatsDto()
                }

                _uiState.update {
                    it.copy(
                        currentReport = existingReport,
                        attendanceStats = savedStats,
                        subjectRatings = ratingsMap,
                        strengths = existingReport.strengths ?: "",
                        improvements = existingReport.improvements ?: "",
                        recommendations = existingReport.recommendations ?: "",
                        revisionNotes = existingReport.revisionNotes,
                        isLoading = false
                    )
                }
            } else {
                // Initialize clean form with default ratings
                val cleanRatings = defaultSubjects.associateWith { Pair("ممتاز", "") }
                _uiState.update {
                    it.copy(
                        currentReport = null,
                        attendanceStats = stats ?: AttendanceStatsDto(),
                        subjectRatings = cleanRatings,
                        strengths = "",
                        improvements = "",
                        recommendations = "",
                        revisionNotes = null,
                        isLoading = false
                    )
                }
            }
        }
    }

    fun updateRating(subject: String, rating: String, notes: String) {
        _uiState.update { state ->
            val updated = state.subjectRatings.toMutableMap()
            updated[subject] = Pair(rating, notes)
            state.copy(subjectRatings = updated)
        }
    }

    fun updateStrengths(text: String) {
        _uiState.update { it.copy(strengths = text) }
    }

    fun updateImprovements(text: String) {
        _uiState.update { it.copy(improvements = text) }
    }

    fun updateRecommendations(text: String) {
        _uiState.update { it.copy(recommendations = text) }
    }

    fun saveDraft() {
        submitWithAction("save_draft")
    }

    fun submitReport() {
        submitWithAction("submit")
    }

    private fun submitWithAction(action: String) {
        val state = _uiState.value
        val studentId = state.selectedStudentId ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null, successMessage = null) }
            val ratingsList = state.subjectRatings.map { (subj, pair) ->
                SubjectRatingDto(
                    subject = subj,
                    rating = pair.first,
                    notes = pair.second.ifBlank { null }
                )
            }
            val request = UpsertMonthlyReportRequest(
                studentId = studentId,
                roomId = roomId,
                month = state.selectedMonth,
                attendanceStats = state.attendanceStats ?: AttendanceStatsDto(),
                subjectRatings = ratingsList,
                strengths = state.strengths.ifBlank { null },
                improvements = state.improvements.ifBlank { null },
                recommendations = state.recommendations.ifBlank { null },
                action = action
            )
            val result = monthlyReportsRepository.upsertReport(request)
            if (result.isSuccess) {
                val actionLabel = if (action == "submit") "تم إرسال التقرير للمشرفة بنجاح" else "تم حفظ المسودة بنجاح"
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        successMessage = actionLabel
                    )
                }
                loadReportForStudent(studentId, state.selectedMonth)
            } else {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = result.exceptionOrNull()?.message ?: "حدث خطأ أثناء حفظ التقرير"
                    )
                }
            }
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(successMessage = null, errorMessage = null) }
    }
}
