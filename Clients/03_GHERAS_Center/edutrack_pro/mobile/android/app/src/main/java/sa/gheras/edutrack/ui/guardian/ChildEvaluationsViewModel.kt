package sa.gheras.edutrack.ui.guardian

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import sa.gheras.edutrack.data.entity.EvaluationEntity
import sa.gheras.edutrack.data.repo.EvaluationsRepository
import sa.gheras.edutrack.data.repo.StudentsRepository

data class ChildEvaluationsUiState(
    val studentName: String = "",
    val availableSubjects: List<String> = emptyList(),
    val selectedSubject: String? = null,
    val evaluations: List<EvaluationEntity> = emptyList(),
    val isLoading: Boolean = true
)

class ChildEvaluationsViewModel(
    val studentId: String,
    private val studentsRepository: StudentsRepository,
    private val evaluationsRepository: EvaluationsRepository
) : ViewModel() {

    private val _selectedSubject = MutableStateFlow<String?>(null)
    val selectedSubject: StateFlow<String?> = _selectedSubject.asStateFlow()

    val uiState: StateFlow<ChildEvaluationsUiState> = combine(
        evaluationsRepository.observeByStudent(studentId),
        _selectedSubject
    ) { evals, subjectFilter ->
        val student = studentsRepository.getById(studentId)
        val subjects = evals.map { it.subject }.distinct()

        val filtered = if (subjectFilter == null) {
            evals
        } else {
            evals.filter { it.subject == subjectFilter }
        }.sortedByDescending { it.date }

        ChildEvaluationsUiState(
            studentName = student?.name ?: "الطالب",
            availableSubjects = subjects,
            selectedSubject = subjectFilter,
            evaluations = filtered,
            isLoading = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChildEvaluationsUiState(isLoading = true))

    fun selectSubject(subject: String?) {
        _selectedSubject.value = subject
    }
}
