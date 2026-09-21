package sa.gheras.edutrack.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import sa.gheras.edutrack.data.db.GherasDatabase
import sa.gheras.edutrack.data.db.ScopePayload
import sa.gheras.edutrack.data.local.session.Role
import sa.gheras.edutrack.data.local.session.SessionStore
import sa.gheras.edutrack.data.remote.MeApi
import sa.gheras.edutrack.data.repo.mappers.AcademicMappers
import sa.gheras.edutrack.data.repo.mappers.AssignmentMappers
import sa.gheras.edutrack.data.repo.mappers.FeesMappers
import sa.gheras.edutrack.data.repo.mappers.NotificationMappers
import sa.gheras.edutrack.data.repo.mappers.ScheduleMappers
import sa.gheras.edutrack.data.repo.mappers.StudentMappers
import java.time.Instant

sealed interface SyncStatus {
    data object Idle : SyncStatus
    data object Syncing : SyncStatus
    data class Error(val message: String) : SyncStatus
}

class PullSync(
    private val meApi: MeApi,
    private val db: GherasDatabase,
    private val sessionStore: SessionStore,
    private val outbox: Outbox
) {

    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val _lastSyncAt = MutableStateFlow<Instant?>(null)
    val lastSyncAt: StateFlow<Instant?> = _lastSyncAt.asStateFlow()

    suspend fun requestFull(): Boolean {
        if (_status.value is SyncStatus.Syncing) return false
        _status.value = SyncStatus.Syncing

        return try {
            val user = sessionStore.user ?: return false
            val isTeacher = user.role == Role.TEACHER

            // 1. Profile
            val profile = meApi.profile()
            sessionStore.saveProfile(profile)

            // 2. Rooms (teacher only; for guardian synthesized from students)
            val rooms = if (isTeacher) {
                val roomDtoList = meApi.listRooms(limit = 500).items
                roomDtoList.map { StudentMappers.roomToEntity(it) }
            } else emptyList()

            // 3. Students
            val studentDtoList = meApi.listStudents(limit = 500).items
            val studentEntities = studentDtoList.map { StudentMappers.toEntity(it) }

            // Guardian room synthesis if guardian
            val allRooms = if (!isTeacher) {
                StudentMappers.synthesizeRooms(studentDtoList)
            } else rooms

            // 4. Schedules
            val scheduleDtoList = meApi.listSchedule(limit = 500).items
            val scheduleEntities = scheduleDtoList.map { ScheduleMappers.toEntity(it) }

            // 5. Attendance (past 60 days)
            val attDtoList = meApi.listAttendance(limit = 500).items
            val attEntities = attDtoList.map { AcademicMappers.attendanceToEntity(it) }

            // 6. Evaluations
            val evalDtoList = meApi.listEvaluations(limit = 500).items
            val evalEntities = evalDtoList.map { AcademicMappers.evaluationToEntity(it) }

            // 7. Assignments & Submissions
            val asgnDtoList = meApi.listAssignments(limit = 500).items
            val asgnEntities = asgnDtoList.map { AssignmentMappers.assignmentToEntity(it) }
            val asgnStudentEntities = asgnDtoList.flatMap { AssignmentMappers.assignmentStudentsToEntities(it) }

            val subDtoList = meApi.listSubmissions(limit = 500).items
            val subEntities = subDtoList.map { AssignmentMappers.submissionToEntity(it) }
            val subFileEntities = subDtoList.flatMap { AssignmentMappers.submissionFilesToEntities(it) }

            // 8. Lesson logs
            val lessonDtoList = meApi.listLessonLogs(limit = 500).items
            val lessonEntities = lessonDtoList.map { AcademicMappers.lessonLogToEntity(it) }

            // 9. Skill progress
            val skillDtoList = meApi.listSkillProgress(limit = 500).items
            val skillEntities = skillDtoList.map { AcademicMappers.skillProgressToEntity(it) }

            // 10. Notifications
            val notifDtoList = meApi.listNotifications(limit = 500).items
            val notifEntities = notifDtoList.map { NotificationMappers.toEntity(it, user.id) }

            // 11. Fees (guardian only)
            val instEntities = if (!isTeacher) {
                val instDtoList = meApi.listInstallments(limit = 500).items
                instDtoList.map { FeesMappers.installmentToEntity(it) }
            } else emptyList()

            val rcptEntities = if (!isTeacher) {
                val rcptDtoList = meApi.listReceipts(limit = 500).items
                rcptDtoList.map { FeesMappers.receiptToEntity(it) }
            } else emptyList()

            val payload = ScopePayload(
                rooms = allRooms,
                schedules = scheduleEntities,
                students = studentEntities,
                installments = instEntities,
                receipts = rcptEntities,
                attendance = attEntities,
                evaluations = evalEntities,
                skillProgress = skillEntities,
                lessonLogs = lessonEntities,
                assignments = asgnEntities,
                assignmentStudents = asgnStudentEntities,
                submissions = subEntities,
                submissionFiles = subFileEntities,
                notifications = notifEntities
            )

            // Commit atomic replaceAll with reverse-dependency topological delete
            db.replaceAll(payload)

            val now = Instant.now()
            _lastSyncAt.value = now
            _status.value = SyncStatus.Idle
            true
        } catch (e: Exception) {
            _status.value = SyncStatus.Error(e.message ?: "فشل التزامن")
            false
        }
    }
}
