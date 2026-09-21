package sa.gheras.edutrack.ui.guardian

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import sa.gheras.edutrack.data.dao.LessonLogDao
import sa.gheras.edutrack.data.dao.ScheduleDao
import sa.gheras.edutrack.data.entity.LessonLogEntity
import sa.gheras.edutrack.data.entity.ScheduleEntity
import sa.gheras.edutrack.data.repo.StudentsRepository
import java.time.LocalDate

data class LessonLogItem(
    val log: LessonLogEntity,
    val subject: String,
    val date: LocalDate
)

data class ChildLessonsUiState(
    val studentName: String = "",
    val items: List<LessonLogItem> = emptyList(),
    val isLoading: Boolean = true
)

class ChildLessonsViewModel(
    val studentId: String,
    private val studentsRepository: StudentsRepository,
    private val scheduleDao: ScheduleDao,
    private val lessonLogDao: LessonLogDao
) : ViewModel() {

    val uiState: StateFlow<ChildLessonsUiState> = combine(
        scheduleDao.observeAll(),
        lessonLogDao.observeBetween(LocalDate.now().minusDays(60), LocalDate.now().plusDays(1))
    ) { schedules, logs ->
        val student = studentsRepository.getById(studentId)
        val scheduleMap = schedules.associateBy { it.id }

        val items = logs.map { log ->
            val sched = scheduleMap[log.scheduleId]
            LessonLogItem(
                log = log,
                subject = sched?.subject ?: "الحصة",
                date = log.date
            )
        }.sortedByDescending { it.date }

        ChildLessonsUiState(
            studentName = student?.name ?: "الطالب",
            items = items,
            isLoading = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChildLessonsUiState(isLoading = true))
}
