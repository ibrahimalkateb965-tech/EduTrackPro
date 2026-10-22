package sa.gheras.edutrack.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.withTransaction
import sa.gheras.edutrack.data.dao.AssignmentDao
import sa.gheras.edutrack.data.dao.AssignmentStudentDao
import sa.gheras.edutrack.data.dao.AttendanceExcuseDao
import sa.gheras.edutrack.data.dao.DailySummaryDao
import sa.gheras.edutrack.data.dao.EvaluationDao
import sa.gheras.edutrack.data.dao.InstallmentDao
import sa.gheras.edutrack.data.dao.LessonLogDao
import sa.gheras.edutrack.data.dao.NotificationDao
import sa.gheras.edutrack.data.dao.PickupDao
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
import sa.gheras.edutrack.data.dao.ThreadDao
import sa.gheras.edutrack.data.entity.AssignmentEntity
import sa.gheras.edutrack.data.entity.AssignmentStudentEntity
import sa.gheras.edutrack.data.entity.AttendanceExcuseEntity
import sa.gheras.edutrack.data.entity.DailySummaryEntity
import sa.gheras.edutrack.data.entity.DailySummaryNoteEntity
import sa.gheras.edutrack.data.entity.EvaluationEntity
import sa.gheras.edutrack.data.entity.InstallmentEntity
import sa.gheras.edutrack.data.entity.LessonLogEntity
import sa.gheras.edutrack.data.entity.NotificationEntity
import sa.gheras.edutrack.data.entity.PickupDelegateEntity
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
import sa.gheras.edutrack.data.entity.ThreadEntity
import sa.gheras.edutrack.data.entity.ThreadMessageEntity

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AssignmentEntity::class,
        AssignmentStudentEntity::class,
        AttendanceExcuseEntity::class,
        DailySummaryEntity::class,
        DailySummaryNoteEntity::class,
        EvaluationEntity::class,
        InstallmentEntity::class,
        LessonLogEntity::class,
        NotificationEntity::class,
        PendingWriteEntity::class,
        PickupDelegateEntity::class,
        ReceiptEntity::class,
        RoomEntity::class,
        ScheduleEntity::class,
        SkillProgressEntity::class,
        StudentAttendanceEntity::class,
        StudentEntity::class,
        SubmissionEntity::class,
        SubmissionFileEntity::class,
        SyncStateEntity::class,
        ThreadEntity::class,
        ThreadMessageEntity::class
    ],
    version = 8,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class GherasDatabase : RoomDatabase() {

    abstract fun assignmentDao(): AssignmentDao
    abstract fun assignmentStudentDao(): AssignmentStudentDao
    abstract fun attendanceExcuseDao(): AttendanceExcuseDao
    abstract fun dailySummaryDao(): DailySummaryDao
    abstract fun evaluationDao(): EvaluationDao
    abstract fun installmentDao(): InstallmentDao
    abstract fun lessonLogDao(): LessonLogDao
    abstract fun notificationDao(): NotificationDao
    abstract fun pickupDao(): PickupDao
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
    abstract fun threadDao(): ThreadDao

    /**
     * Replaces the whole synced scope in ONE transaction (design spec §2.3).
     *
     * Clears run in reverse-dependency order so every `RESTRICT` foreign key holds at each step:
     * submission_files → submissions → assignment_students → assignments →
     * student_attendance / evaluations / skill_progress / lesson_logs →
     * pickup_delegates → receipts → installments → students →
     * schedules → rooms (notifications are FK-free).
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
        dailySummaryDao().clearNotes()
        threadDao().clearMessages()
        dailySummaryDao().clearSummaries()
        threadDao().clearThreads()
        submissionFileDao().clear()
        submissionDao().clear()
        assignmentStudentDao().clear()
        assignmentDao().clear()
        attendanceExcuseDao().clear()
        studentAttendanceDao().clear()
        evaluationDao().clear()
        skillProgressDao().clear()
        lessonLogDao().clear()
        receiptDao().clearAll()
        installmentDao().clearAll()
        pickupDao().clearAll()
        studentDao().clear()
        scheduleDao().clear()
        roomDao().clear()
        notificationDao().clear()

        // 2. Insert — parents before children.
        roomDao().upsertAll(payload.rooms)
        scheduleDao().upsertAll(payload.schedules)
        studentDao().upsertAll(payload.students)
        pickupDao().upsertAll(payload.pickupDelegates)
        installmentDao().upsertAll(payload.installments)
        receiptDao().upsertAll(payload.receipts)
        studentAttendanceDao().upsertAll(payload.attendance)
        attendanceExcuseDao().upsertAll(payload.excuses)
        evaluationDao().upsertAll(payload.evaluations)
        skillProgressDao().upsertAll(payload.skillProgress)
        lessonLogDao().upsertAll(payload.lessonLogs)
        dailySummaryDao().upsertSummaries(payload.dailySummaries)
        dailySummaryDao().upsertNotes(payload.dailySummaryNotes)
        threadDao().upsertThreads(payload.threads)
        threadDao().upsertMessages(payload.threadMessages)
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

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notifications ADD COLUMN target_type TEXT")
                db.execSQL("ALTER TABLE notifications ADD COLUMN target_id TEXT")
                db.execSQL("ALTER TABLE notifications ADD COLUMN priority TEXT NOT NULL DEFAULT 'normal'")
                db.execSQL("ALTER TABLE notifications ADD COLUMN action_url TEXT")
                db.execSQL("ALTER TABLE notifications ADD COLUMN sender_user_id TEXT")
                db.execSQL("ALTER TABLE notifications ADD COLUMN sender_name TEXT")
                db.execSQL("ALTER TABLE notifications ADD COLUMN broadcast_id TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_notifications_user_id_deleted_at ON notifications(user_id, deleted_at)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    DELETE FROM evaluations WHERE rowid NOT IN (
                        SELECT MAX(rowid) FROM evaluations GROUP BY student_id, date, subject, eval_type
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_evaluations_student_id_date_subject_eval_type ON evaluations(student_id, date, subject, eval_type)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE students ADD COLUMN photo_url TEXT")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `pickup_delegates` (
                        `id` TEXT NOT NULL,
                        `student_id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `phone` TEXT NOT NULL,
                        `relation` TEXT NOT NULL,
                        `is_approved` INTEGER NOT NULL DEFAULT 1,
                        `client_request_id` TEXT,
                        `national_id_masked` TEXT,
                        `created_at` TEXT,
                        `updated_at` TEXT,
                        `deleted_at` TEXT,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`student_id`) REFERENCES `students`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_pickup_delegates_student_id` ON `pickup_delegates` (`student_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_pickup_delegates_client_request_id` ON `pickup_delegates` (`client_request_id`)")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Rebuild assignments table to make due_date nullable and add is_draft
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `assignments_new` (
                        `id` TEXT NOT NULL,
                        `branch_id` TEXT,
                        `title` TEXT NOT NULL,
                        `subject` TEXT,
                        `kind` TEXT,
                        `due_date` TEXT,
                        `teacher_user_id` TEXT,
                        `instructions` TEXT,
                        `page_ref` TEXT,
                        `is_draft` INTEGER NOT NULL DEFAULT 0,
                        `created_at` TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
                        `updated_at` TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
                        `deleted_at` TEXT,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO `assignments_new` (
                        `id`, `branch_id`, `title`, `subject`, `kind`, `due_date`,
                        `teacher_user_id`, `instructions`, `page_ref`, `is_draft`,
                        `created_at`, `updated_at`, `deleted_at`
                    )
                    SELECT 
                        `id`, `branch_id`, `title`, `subject`, `kind`, `due_date`,
                        `teacher_user_id`, `instructions`, `page_ref`, 0,
                        `created_at`, `updated_at`, `deleted_at`
                    FROM `assignments`
                """.trimIndent())
                db.execSQL("DROP TABLE `assignments`")
                db.execSQL("ALTER TABLE `assignments_new` RENAME TO `assignments`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_assignments_teacher_user_id` ON `assignments` (`teacher_user_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_assignments_due_date` ON `assignments` (`due_date`)")

                // 2. Add new columns and indices to submissions table
                db.execSQL("ALTER TABLE `submissions` ADD COLUMN `attempt_number` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `submissions` ADD COLUMN `parent_submission_id` TEXT")
                db.execSQL("ALTER TABLE `submissions` ADD COLUMN `grade_level` TEXT")
                db.execSQL("ALTER TABLE `submissions` ADD COLUMN `revision_note` TEXT")
                db.execSQL("ALTER TABLE `submissions` ADD COLUMN `teacher_feedback_key` TEXT")
                db.execSQL("ALTER TABLE `submissions` ADD COLUMN `is_late` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_submissions_assignment_id_student_id_attempt_number` ON `submissions` (`assignment_id`, `student_id`, `attempt_number`)")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Add columns to student_attendance
                db.execSQL("ALTER TABLE `student_attendance` ADD COLUMN `is_draft` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `student_attendance` ADD COLUMN `excuse_id` TEXT")

                // 2. Create attendance_excuses table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `attendance_excuses` (
                        `id` TEXT NOT NULL,
                        `branch_id` TEXT,
                        `student_id` TEXT NOT NULL,
                        `excuse_date` TEXT NOT NULL,
                        `reason_text` TEXT NOT NULL,
                        `attachment_storage_key` TEXT,
                        `status` TEXT NOT NULL DEFAULT 'pending',
                        `supervisor_notes` TEXT,
                        `reviewed_at` TEXT,
                        `client_request_id` TEXT,
                        `created_at` TEXT NOT NULL,
                        `updated_at` TEXT NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`student_id`) REFERENCES `students`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_attendance_excuses_student_id` ON `attendance_excuses` (`student_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_attendance_excuses_excuse_date` ON `attendance_excuses` (`excuse_date`)")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Create threads table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `threads` (
                        `id` TEXT NOT NULL,
                        `branch_id` TEXT,
                        `student_id` TEXT NOT NULL,
                        `guardian_user_id` TEXT NOT NULL,
                        `staff_user_id` TEXT NOT NULL,
                        `subject` TEXT NOT NULL,
                        `linked_assignment_id` TEXT,
                        `linked_summary_id` TEXT,
                        `status` TEXT NOT NULL DEFAULT 'open',
                        `last_message_at` TEXT NOT NULL,
                        `last_message_preview` TEXT,
                        `created_at` TEXT NOT NULL,
                        `updated_at` TEXT NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`student_id`) REFERENCES `students`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_threads_student_id` ON `threads` (`student_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_threads_last_message_at` ON `threads` (`last_message_at`)")

                // 2. Create thread_messages table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `thread_messages` (
                        `id` TEXT NOT NULL,
                        `branch_id` TEXT,
                        `thread_id` TEXT NOT NULL,
                        `sender_user_id` TEXT NOT NULL,
                        `sender_role` TEXT NOT NULL,
                        `body` TEXT NOT NULL,
                        `attachment_storage_key` TEXT,
                        `attachment_type` TEXT,
                        `attachment_name` TEXT,
                        `attachment_size_bytes` INTEGER,
                        `sent_at` TEXT NOT NULL,
                        `delivered_at` TEXT,
                        `read_at` TEXT,
                        `client_message_id` TEXT,
                        `created_at` TEXT NOT NULL,
                        `updated_at` TEXT NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`thread_id`) REFERENCES `threads`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_thread_messages_thread_id` ON `thread_messages` (`thread_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_thread_messages_sent_at` ON `thread_messages` (`sent_at`)")

                // 3. Create daily_summaries table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `daily_summaries` (
                        `id` TEXT NOT NULL,
                        `branch_id` TEXT,
                        `room_id` TEXT NOT NULL,
                        `teacher_user_id` TEXT NOT NULL,
                        `summary_date` TEXT NOT NULL,
                        `subject` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `class_summary` TEXT NOT NULL,
                        `homework_summary` TEXT,
                        `is_published` INTEGER NOT NULL DEFAULT 0,
                        `published_at` TEXT,
                        `created_at` TEXT NOT NULL,
                        `updated_at` TEXT NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`room_id`) REFERENCES `rooms`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_daily_summaries_room_id` ON `daily_summaries` (`room_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_daily_summaries_summary_date` ON `daily_summaries` (`summary_date`)")

                // 4. Create daily_summary_notes table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `daily_summary_notes` (
                        `id` TEXT NOT NULL,
                        `branch_id` TEXT,
                        `summary_id` TEXT NOT NULL,
                        `student_id` TEXT NOT NULL,
                        `private_note` TEXT NOT NULL,
                        `created_at` TEXT NOT NULL,
                        `updated_at` TEXT NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`summary_id`) REFERENCES `daily_summaries`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`student_id`) REFERENCES `students`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_daily_summary_notes_summary_id` ON `daily_summary_notes` (`summary_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_daily_summary_notes_student_id` ON `daily_summary_notes` (`student_id`)")
            }
        }
    }
}
