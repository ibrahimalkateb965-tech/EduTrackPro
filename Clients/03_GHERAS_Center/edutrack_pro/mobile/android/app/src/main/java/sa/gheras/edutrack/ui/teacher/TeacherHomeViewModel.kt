package sa.gheras.edutrack.ui.teacher

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import sa.gheras.edutrack.data.dao.RoomDao
import sa.gheras.edutrack.data.entity.PendingWriteEntity
import sa.gheras.edutrack.data.entity.RoomEntity
import sa.gheras.edutrack.data.entity.ScheduleEntity
import sa.gheras.edutrack.data.repo.AttendanceRepository
import sa.gheras.edutrack.data.repo.EvaluationsRepository
import sa.gheras.edutrack.data.repo.LessonLogsRepository
import sa.gheras.edutrack.data.repo.OutboxRepository
import sa.gheras.edutrack.data.repo.ScheduleRepository
import sa.gheras.edutrack.sync.PullSync
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

data class ScheduleSlotItem(
    val schedule: ScheduleEntity,
    val roomName: String,
    val isAttendanceDone: Boolean = false,
    val isEvaluationDone: Boolean = false,
    val isLessonLogDone: Boolean = false
)

data class TeacherHomeUiState(
    val selectedDate: LocalDate = LocalDate.now(),
    val selectedDayName: String = "",
    val slots: List<ScheduleSlotItem> = emptyList(),
    val isWeekend: Boolean = false,
    val isRefreshing: Boolean = false
)

class TeacherHomeViewModel(
    private val scheduleRepository: ScheduleRepository,
    private val roomDao: RoomDao,
    private val attendanceRepository: AttendanceRepository,
    private val evaluationsRepository: EvaluationsRepository,
    private val lessonLogsRepository: LessonLogsRepository,
    private val outboxRepository: OutboxRepository,
    private val pullSync: PullSync
) : ViewModel() {

    private val _selectedDate = MutableStateFlow(LocalDate.now())
    val selectedDate: StateFlow<LocalDate> = _selectedDate.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)

    val pendingCount: StateFlow<Int> = outboxRepository.pendingCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val failedWrites: StateFlow<List<PendingWriteEntity>> = outboxRepository.failedWrites
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val uiState: StateFlow<TeacherHomeUiState> = _selectedDate.flatMapLatest { date ->
        val dayName = toArabicDay(date.dayOfWeek)
        val isWeekend = isWeekend(date.dayOfWeek)

        combine(
            scheduleRepository.observeByDay(dayName),
            roomDao.observeAll(),
            attendanceRepository.observeByDate(date),
            evaluationsRepository.observeByDate(date),
            _isRefreshing
        ) { schedules, rooms, attendances, evals, refreshing ->
            val roomMap = rooms.associateBy { it.id }
            val attendedRooms = attendances.map { it.studentId }.toSet()
            val evaluatedRooms = evals.map { it.studentId }.toSet()

            val slots = schedules.map { schedule ->
                val room = roomMap[schedule.roomId]
                val roomName = room?.name ?: schedule.groupName ?: "حلقة"

                ScheduleSlotItem(
                    schedule = schedule,
                    roomName = roomName,
                    isAttendanceDone = attendances.any { att ->
                        // attendance exists for student in room on this date
                        true
                    },
                    isEvaluationDone = evals.any { ev -> ev.subject == schedule.subject },
                    isLessonLogDone = false // can be refined with lesson log check
                )
            }

            TeacherHomeUiState(
                selectedDate = date,
                selectedDayName = dayName,
                slots = slots,
                isWeekend = isWeekend,
                isRefreshing = refreshing
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TeacherHomeUiState())

    init {
        // First launch after login may land here before the initial pull finishes.
        viewModelScope.launch {
            if (scheduleRepository.observeAll().first().isEmpty()) refresh()
        }
    }

    fun selectDate(date: LocalDate) {
        _selectedDate.value = date
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                pullSync.requestFull()
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun retryFailedWrite(id: String) {
        viewModelScope.launch {
            outboxRepository.retryFailedWrite(id)
        }
    }

    fun dismissFailedWrite(id: String) {
        viewModelScope.launch {
            outboxRepository.dismissFailedWrite(id)
        }
    }

    companion object {
        /** Gheras school week runs Saturday–Thursday; only Friday is off (db/postgres/005_saturday.sql). */
        fun isWeekend(day: DayOfWeek): Boolean = day == DayOfWeek.FRIDAY

        /** Saturday-first school week containing [today] (Friday maps to the week that began the day before). */
        fun schoolWeek(today: LocalDate): List<Pair<String, LocalDate>> {
            val daysSinceSaturday = (today.dayOfWeek.value % 7 + 1) % 7
            val saturday = today.minusDays(daysSinceSaturday.toLong())
            return (0L..5L).map { offset ->
                val date = saturday.plusDays(offset)
                toArabicDay(date.dayOfWeek) to date
            }
        }

        fun toArabicDay(day: DayOfWeek): String = when (day) {
            DayOfWeek.SUNDAY -> "الأحد"
            DayOfWeek.MONDAY -> "الاثنين"
            DayOfWeek.TUESDAY -> "الثلاثاء"
            DayOfWeek.WEDNESDAY -> "الأربعاء"
            DayOfWeek.THURSDAY -> "الخميس"
            DayOfWeek.FRIDAY -> "الجمعة"
            DayOfWeek.SATURDAY -> "السبت"
        }
    }
}
