package sa.gheras.edutrack.data.repo.mappers

import sa.gheras.edutrack.data.entity.EvaluationEntity
import sa.gheras.edutrack.data.entity.LessonLogEntity
import sa.gheras.edutrack.data.entity.SkillProgressEntity
import sa.gheras.edutrack.data.entity.StudentAttendanceEntity
import sa.gheras.edutrack.data.remote.dto.AttendanceDto
import sa.gheras.edutrack.data.remote.dto.EvaluationDto
import sa.gheras.edutrack.data.remote.dto.LessonLogDto
import sa.gheras.edutrack.data.remote.dto.SkillProgressDto
import java.time.LocalDate

object AcademicMappers {

    fun attendanceToEntity(dto: AttendanceDto, defaultBranchId: String? = null): StudentAttendanceEntity {
        return StudentAttendanceEntity(
            id = dto.id,
            branchId = defaultBranchId,
            studentId = dto.studentId,
            date = DateParsers.parseLocalDate(dto.date) ?: LocalDate.now(),
            status = dto.status,
            note = dto.excuseNote,
            recordedByUserId = dto.recordedByUserId,
            createdAt = DateParsers.parseInstant(dto.createdAt),
            updatedAt = DateParsers.parseInstant(dto.updatedAt),
            deletedAt = dto.deletedAt?.let { DateParsers.parseInstant(it) }
        )
    }

    fun evaluationToEntity(dto: EvaluationDto, defaultBranchId: String? = null): EvaluationEntity {
        return EvaluationEntity(
            id = dto.id,
            branchId = defaultBranchId,
            studentId = dto.studentId,
            subject = dto.subject ?: "عام",
            evalType = dto.evalType ?: "daily",
            date = DateParsers.parseLocalDate(dto.date) ?: LocalDate.now(),
            value = dto.value ?: dto.score ?: 0.0,
            teacherUserId = dto.teacherUserId ?: dto.recordedByUserId,
            createdAt = DateParsers.parseInstant(dto.createdAt),
            updatedAt = DateParsers.parseInstant(dto.updatedAt),
            deletedAt = dto.deletedAt?.let { DateParsers.parseInstant(it) }
        )
    }

    fun lessonLogToEntity(dto: LessonLogDto): LessonLogEntity {
        return LessonLogEntity(
            id = dto.id,
            branchId = dto.branchId,
            scheduleId = dto.scheduleId,
            date = DateParsers.parseLocalDate(dto.date) ?: LocalDate.now(),
            status = dto.status,
            covered = dto.covered,
            homework = dto.homework,
            notes = dto.notes,
            teacherUserId = dto.teacherUserId,
            createdAt = DateParsers.parseInstant(dto.createdAt),
            updatedAt = DateParsers.parseInstant(dto.updatedAt),
            deletedAt = null
        )
    }

    fun skillProgressToEntity(dto: SkillProgressDto, defaultBranchId: String? = null): SkillProgressEntity {
        return SkillProgressEntity(
            id = dto.id,
            branchId = dto.branchId ?: defaultBranchId,
            studentId = dto.studentId,
            subject = dto.subject,
            skill = dto.skill ?: dto.subject,
            level = dto.level ?: dto.score?.toString() ?: "متقن",
            date = DateParsers.parseLocalDate(dto.date) ?: LocalDate.now(),
            note = dto.note ?: dto.notes,
            createdAt = DateParsers.parseInstant(dto.createdAt),
            updatedAt = DateParsers.parseInstant(dto.updatedAt),
            deletedAt = null
        )
    }
}
