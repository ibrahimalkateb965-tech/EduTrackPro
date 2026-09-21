package sa.gheras.edutrack.ui.guardian

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import sa.gheras.edutrack.data.dao.SkillProgressDao
import sa.gheras.edutrack.data.entity.SkillProgressEntity
import sa.gheras.edutrack.data.repo.StudentsRepository

data class ChildSkillsUiState(
    val studentName: String = "",
    val skills: List<SkillProgressEntity> = emptyList(),
    val isLoading: Boolean = true
)

class ChildSkillsViewModel(
    val studentId: String,
    private val studentsRepository: StudentsRepository,
    private val skillProgressDao: SkillProgressDao
) : ViewModel() {

    val uiState: StateFlow<ChildSkillsUiState> = combine(
        skillProgressDao.observeLatestByStudent(studentId),
        skillProgressDao.observeByStudent(studentId)
    ) { latestSkills, allSkills ->
        val student = studentsRepository.getById(studentId)
        val displaySkills = latestSkills.ifEmpty { allSkills }

        ChildSkillsUiState(
            studentName = student?.name ?: "الطالب",
            skills = displaySkills,
            isLoading = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChildSkillsUiState(isLoading = true))
}
