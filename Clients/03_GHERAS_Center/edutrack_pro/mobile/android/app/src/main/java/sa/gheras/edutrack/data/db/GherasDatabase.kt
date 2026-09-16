package sa.gheras.edutrack.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import sa.gheras.edutrack.data.dao.AssignmentDao
import sa.gheras.edutrack.data.dao.AssignmentStudentDao
import sa.gheras.edutrack.data.dao.EvaluationDao
import sa.gheras.edutrack.data.dao.GuardianDao
import sa.gheras.edutrack.data.dao.LessonLogDao
import sa.gheras.edutrack.data.dao.NotificationDao
import sa.gheras.edutrack.data.dao.RoomDao
import sa.gheras.edutrack.data.dao.ScheduleDao
import sa.gheras.edutrack.data.dao.SkillProgressDao
import sa.gheras.edutrack.data.dao.StudentAttendanceDao
import sa.gheras.edutrack.data.dao.StudentDao
import sa.gheras.edutrack.data.dao.StudyPlanDao
import sa.gheras.edutrack.data.dao.SubmissionDao
import sa.gheras.edutrack.data.dao.SubmissionFileDao
import sa.gheras.edutrack.data.dao.UserDao
import sa.gheras.edutrack.data.entity.AssignmentEntity
import sa.gheras.edutrack.data.entity.AssignmentStudentEntity
import sa.gheras.edutrack.data.entity.EvaluationEntity
import sa.gheras.edutrack.data.entity.GuardianEntity
import sa.gheras.edutrack.data.entity.LessonLogEntity
import sa.gheras.edutrack.data.entity.NotificationEntity
import sa.gheras.edutrack.data.entity.RoomEntity
import sa.gheras.edutrack.data.entity.ScheduleEntity
import sa.gheras.edutrack.data.entity.SkillProgressEntity
import sa.gheras.edutrack.data.entity.StudentAttendanceEntity
import sa.gheras.edutrack.data.entity.StudentEntity
import sa.gheras.edutrack.data.entity.StudyPlanEntity
import sa.gheras.edutrack.data.entity.SubmissionEntity
import sa.gheras.edutrack.data.entity.SubmissionFileEntity
import sa.gheras.edutrack.data.entity.UserEntity

@Database(
    entities = [
        AssignmentEntity::class,
        AssignmentStudentEntity::class,
        EvaluationEntity::class,
        GuardianEntity::class,
        LessonLogEntity::class,
        NotificationEntity::class,
        RoomEntity::class,
        ScheduleEntity::class,
        SkillProgressEntity::class,
        StudentAttendanceEntity::class,
        StudentEntity::class,
        StudyPlanEntity::class,
        SubmissionEntity::class,
        SubmissionFileEntity::class,
        UserEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class GherasDatabase : RoomDatabase() {

    abstract fun assignmentDao(): AssignmentDao
    abstract fun assignmentStudentDao(): AssignmentStudentDao
    abstract fun evaluationDao(): EvaluationDao
    abstract fun guardianDao(): GuardianDao
    abstract fun lessonLogDao(): LessonLogDao
    abstract fun notificationDao(): NotificationDao
    abstract fun roomDao(): RoomDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun skillProgressDao(): SkillProgressDao
    abstract fun studentAttendanceDao(): StudentAttendanceDao
    abstract fun studentDao(): StudentDao
    abstract fun studyPlanDao(): StudyPlanDao
    abstract fun submissionDao(): SubmissionDao
    abstract fun submissionFileDao(): SubmissionFileDao
    abstract fun userDao(): UserDao

    companion object {
        const val DATABASE_NAME = "gheras_edutrack"
    }
}
