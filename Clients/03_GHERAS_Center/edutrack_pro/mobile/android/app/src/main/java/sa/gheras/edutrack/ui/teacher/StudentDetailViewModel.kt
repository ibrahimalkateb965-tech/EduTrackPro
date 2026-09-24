package sa.gheras.edutrack.ui.teacher

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import sa.gheras.edutrack.data.dao.RoomDao
import sa.gheras.edutrack.data.dao.SkillProgressDao
import sa.gheras.edutrack.data.entity.EvaluationEntity
import sa.gheras.edutrack.data.entity.SkillProgressEntity
import sa.gheras.edutrack.data.entity.StudentAttendanceEntity
import sa.gheras.edutrack.data.entity.StudentEntity
import sa.gheras.edutrack.data.repo.AttendanceRepository
import sa.gheras.edutrack.data.repo.EvaluationsRepository
import sa.gheras.edutrack.data.repo.StudentsRepository
import java.time.LocalDate

data class AttendanceStats(
    val total: Int = 0,
    val present: Int = 0,
    val absent: Int = 0,
    val late: Int = 0,
    val excused: Int = 0
)

data class StudentDetailUiState(
    val student: StudentEntity? = null,
    val roomName: String = "",
    val attendanceStats: AttendanceStats = AttendanceStats(),
    val latestEvaluations: List<EvaluationEntity> = emptyList(),
    val skillProgress: List<SkillProgressEntity> = emptyList(),
    val isLoading: Boolean = true
)

class StudentDetailViewModel(
    private val studentId: String,
    private val studentsRepository: StudentsRepository,
    private val roomDao: RoomDao,
    private val attendanceRepository: AttendanceRepository,
    private val evaluationsRepository: EvaluationsRepository,
    private val skillProgressDao: SkillProgressDao
) : ViewModel() {

    val uiState: StateFlow<StudentDetailUiState> = combine(
        attendanceRepository.observeByStudent(studentId),
        evaluationsRepository.observeByStudent(studentId),
        skillProgressDao.observeLatestByStudent(studentId)
    ) { attendances, evals, skills ->
        val student = studentsRepository.getById(studentId)
        val room = student?.roomId?.let { roomDao.getById(it) }

        // Filter attendance to last 60 days
        val sixtyDaysAgo = LocalDate.now().minusDays(60)
        val recentAttendances = attendances.filter { !it.date.isBefore(sixtyDaysAgo) }

        val stats = AttendanceStats(
            total = recentAttendances.size,
            present = recentAttendances.count { it.status == "حاضر" },
            absent = recentAttendances.count { it.status == "غائب" },
            late = recentAttendances.count { it.status == "متأخر" },
            excused = recentAttendances.count { it.status == "مستأذن" }
        )

        StudentDetailUiState(
            student = student,
            roomName = room?.name ?: student?.groupName ?: "قاعة غير محددة",
            attendanceStats = stats,
            latestEvaluations = evals.take(10),
            skillProgress = skills,
            isLoading = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StudentDetailUiState(isLoading = true))
}
