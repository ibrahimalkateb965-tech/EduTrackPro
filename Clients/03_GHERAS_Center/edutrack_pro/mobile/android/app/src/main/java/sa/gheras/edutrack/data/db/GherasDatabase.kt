package sa.gheras.edutrack.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.withTransaction
import sa.gheras.edutrack.data.dao.AssignmentDao
import sa.gheras.edutrack.data.dao.AssignmentStudentDao
import sa.gheras.edutrack.data.dao.EvaluationDao
import sa.gheras.edutrack.data.dao.InstallmentDao
import sa.gheras.edutrack.data.dao.LessonLogDao
import sa.gheras.edutrack.data.dao.NotificationDao
import sa.gheras.edutrack.data.dao.PendingWriteDao
import sa.gheras.edutrack.data.dao.ReceiptDao
import sa.gheras.edutrack.data.dao.RoomDao
import sa.gheras.edutrack.data.dao.ScheduleDao
import sa.gheras.edutrack.data.dao.SkillProgressDao
import sa.gheras.edutrack.data.dao.StudentAttendanceDao
import sa.gheras.edutrack.data.dao.StudentDao
import sa.gheras.edutrack.data.dao.SubmissionDao
import sa.gheras.edutrack.data.dao.SubmissionFileDao
import sa.gheras.edutrack.data.dao.SyncStateDao
import sa.gheras.edutrack.data.entity.AssignmentEntity
import sa.gheras.edutrack.data.entity.AssignmentStudentEntity
import sa.gheras.edutrack.data.entity.EvaluationEntity
import sa.gheras.edutrack.data.entity.InstallmentEntity
import sa.gheras.edutrack.data.entity.LessonLogEntity
import sa.gheras.edutrack.data.entity.NotificationEntity
import sa.gheras.edutrack.data.entity.PendingWriteEntity
import sa.gheras.edutrack.data.entity.ReceiptEntity
import sa.gheras.edutrack.data.entity.RoomEntity
import sa.gheras.edutrack.data.entity.ScheduleEntity
import sa.gheras.edutrack.data.entity.SkillProgressEntity
import sa.gheras.edutrack.data.entity.StudentAttendanceEntity
import sa.gheras.edutrack.data.entity.StudentEntity
import sa.gheras.edutrack.data.entity.SubmissionEntity
import sa.gheras.edutrack.data.entity.SubmissionFileEntity
import sa.gheras.edutrack.data.entity.SyncStateEntity

@Database(
    entities = [
        AssignmentEntity::class,
        AssignmentStudentEntity::class,
        EvaluationEntity::class,
        InstallmentEntity::class,
        LessonLogEntity::class,
        NotificationEntity::class,
        PendingWriteEntity::class,
        ReceiptEntity::class,
        RoomEntity::class,
        ScheduleEntity::class,
        SkillProgressEntity::class,
        StudentAttendanceEntity::class,
        StudentEntity::class,
        SubmissionEntity::class,
        SubmissionFileEntity::class,
        SyncStateEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class GherasDatabase : RoomDatabase() {

    abstract fun assignmentDao(): AssignmentDao
    abstract fun assignmentStudentDao(): AssignmentStudentDao
    abstract fun evaluationDao(): EvaluationDao
    abstract fun installmentDao(): InstallmentDao
    abstract fun lessonLogDao(): LessonLogDao
    abstract fun notificationDao(): NotificationDao
    abstract fun pendingWriteDao(): PendingWriteDao
    abstract fun receiptDao(): ReceiptDao
    abstract fun roomDao(): RoomDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun skillProgressDao(): SkillProgressDao
    abstract fun studentAttendanceDao(): StudentAttendanceDao
    abstract fun studentDao(): StudentDao
    abstract fun submissionDao(): SubmissionDao
    abstract fun submissionFileDao(): SubmissionFileDao
    abstract fun syncStateDao(): SyncStateDao

    /**
     * Replaces the whole synced scope in ONE transaction (design spec §2.3).
     *
     * Clears run in reverse-dependency order so every `RESTRICT` foreign key holds at each step:
     * submission_files → submissions → assignment_students → assignments →
     * student_attendance / evaluations / skill_progress / lesson_logs →
     * receipts → installments → students → schedules → rooms (notifications are FK-free).
     * Inserts then run in forward (parent-first) order.
     *
     * `pending_writes` is never cleared by a pull; after the upserts, [reapplyPending] receives the
     * still-queued outbox rows *inside the same transaction* so an offline tick can re-apply its
     * `local:` projection before observers see the new snapshot. `sync_state` is untouched.
     * Any failure rolls the entire replacement back — the previous cache stays intact.
     */
    suspend fun replaceAll(
        payload: ScopePayload,
        reapplyPending: suspend (List<PendingWriteEntity>) -> Unit = {}
    ) = withTransaction {
        // 1. Clear — children before parents.
        submissionFileDao().clear()
        submissionDao().clear()
        assignmentStudentDao().clear()
        assignmentDao().clear()
        studentAttendanceDao().clear()
        evaluationDao().clear()
        skillProgressDao().clear()
        lessonLogDao().clear()
        receiptDao().clearAll()
        installmentDao().clearAll()
        studentDao().clear()
        scheduleDao().clear()
        roomDao().clear()
        notificationDao().clear()

        // 2. Insert — parents before children.
        roomDao().upsertAll(payload.rooms)
        scheduleDao().upsertAll(payload.schedules)
        studentDao().upsertAll(payload.students)
        installmentDao().upsertAll(payload.installments)
        receiptDao().upsertAll(payload.receipts)
        studentAttendanceDao().upsertAll(payload.attendance)
        evaluationDao().upsertAll(payload.evaluations)
        skillProgressDao().upsertAll(payload.skillProgress)
        lessonLogDao().upsertAll(payload.lessonLogs)
        assignmentDao().upsertAll(payload.assignments)
        assignmentStudentDao().upsertAll(payload.assignmentStudents)
        submissionDao().upsertAll(payload.submissions)
        submissionFileDao().upsertAll(payload.submissionFiles)
        notificationDao().upsertAll(payload.notifications)

        // 3. Re-apply whatever the outbox still owes the server, before the snapshot is published.
        val pending = pendingWriteDao().listPending()
        if (pending.isNotEmpty()) reapplyPending(pending)
    }

    companion object {
        const val DATABASE_NAME = "gheras_edutrack"
    }
}
