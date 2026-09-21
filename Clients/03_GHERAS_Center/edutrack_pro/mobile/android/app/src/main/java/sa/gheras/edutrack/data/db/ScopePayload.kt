package sa.gheras.edutrack.data.db

import sa.gheras.edutrack.data.entity.AssignmentEntity
import sa.gheras.edutrack.data.entity.AssignmentStudentEntity
import sa.gheras.edutrack.data.entity.EvaluationEntity
import sa.gheras.edutrack.data.entity.InstallmentEntity
import sa.gheras.edutrack.data.entity.LessonLogEntity
import sa.gheras.edutrack.data.entity.NotificationEntity
import sa.gheras.edutrack.data.entity.ReceiptEntity
import sa.gheras.edutrack.data.entity.RoomEntity
import sa.gheras.edutrack.data.entity.ScheduleEntity
import sa.gheras.edutrack.data.entity.SkillProgressEntity
import sa.gheras.edutrack.data.entity.StudentAttendanceEntity
import sa.gheras.edutrack.data.entity.StudentEntity
import sa.gheras.edutrack.data.entity.SubmissionEntity
import sa.gheras.edutrack.data.entity.SubmissionFileEntity

/**
 * Everything one full pull produces for the signed-in user's scope (design spec §2.3 / §3.1).
 * One list per synced table; `pending_writes` and `sync_state` are deliberately absent —
 * a pull never touches them.
 */
data class ScopePayload(
    val rooms: List<RoomEntity> = emptyList(),
    val schedules: List<ScheduleEntity> = emptyList(),
    val students: List<StudentEntity> = emptyList(),
    val installments: List<InstallmentEntity> = emptyList(),
    val receipts: List<ReceiptEntity> = emptyList(),
    val attendance: List<StudentAttendanceEntity> = emptyList(),
    val evaluations: List<EvaluationEntity> = emptyList(),
    val skillProgress: List<SkillProgressEntity> = emptyList(),
    val lessonLogs: List<LessonLogEntity> = emptyList(),
    val assignments: List<AssignmentEntity> = emptyList(),
    val assignmentStudents: List<AssignmentStudentEntity> = emptyList(),
    val submissions: List<SubmissionEntity> = emptyList(),
    val submissionFiles: List<SubmissionFileEntity> = emptyList(),
    val notifications: List<NotificationEntity> = emptyList()
)
