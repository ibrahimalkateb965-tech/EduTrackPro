package sa.gheras.edutrack.data.repo.mappers

import sa.gheras.edutrack.data.entity.AssignmentEntity
import sa.gheras.edutrack.data.entity.AssignmentStudentEntity
import sa.gheras.edutrack.data.entity.SubmissionEntity
import sa.gheras.edutrack.data.entity.SubmissionFileEntity
import sa.gheras.edutrack.data.remote.dto.AssignmentDto
import sa.gheras.edutrack.data.remote.dto.SubmissionDto
import java.time.LocalDate

object AssignmentMappers {

    fun assignmentToEntity(dto: AssignmentDto, defaultBranchId: String? = null): AssignmentEntity {
        return AssignmentEntity(
            id = dto.id,
            branchId = defaultBranchId,
            title = dto.title,
            subject = dto.subject,
            kind = dto.kind ?: "homework",
            dueDate = dto.dueDate?.let { DateParsers.parseLocalDate(it) },
            teacherUserId = dto.teacherUserId,
            instructions = dto.instructions ?: dto.description,
            pageRef = dto.pageRef,
            isDraft = dto.isDraft,
            createdAt = DateParsers.parseInstant(dto.createdAt),
            updatedAt = DateParsers.parseInstant(dto.updatedAt),
            deletedAt = dto.deletedAt?.let { DateParsers.parseInstant(it) }
        )
    }

    fun assignmentStudentsToEntities(dto: AssignmentDto, defaultBranchId: String? = null): List<AssignmentStudentEntity> {
        val now = DateParsers.parseInstant(dto.createdAt)
        return dto.studentIds.map { studentId ->
            AssignmentStudentEntity(
                id = "${dto.id}:$studentId",
                branchId = defaultBranchId,
                assignmentId = dto.id,
                studentId = studentId,
                createdAt = now,
                updatedAt = now,
                deletedAt = null
            )
        }
    }

    fun submissionToEntity(dto: SubmissionDto, defaultBranchId: String? = null): SubmissionEntity {
        return SubmissionEntity(
            id = dto.id,
            branchId = defaultBranchId,
            assignmentId = dto.assignmentId,
            studentId = dto.studentId,
            attemptNumber = dto.attemptNumber,
            parentSubmissionId = dto.parentSubmissionId,
            gradeLevel = dto.gradeLevel,
            revisionNote = dto.revisionNote,
            teacherFeedbackKey = dto.teacherFeedbackKey,
            isLate = dto.isLate,
            submittedAt = DateParsers.parseInstant(dto.submittedAt),
            status = dto.status ?: "submitted",
            grade = dto.grade,
            feedback = dto.feedback,
            createdAt = DateParsers.parseInstant(dto.createdAt),
            updatedAt = DateParsers.parseInstant(dto.updatedAt),
            deletedAt = dto.deletedAt?.let { DateParsers.parseInstant(it) }
        )
    }

    fun submissionFilesToEntities(dto: SubmissionDto, defaultBranchId: String? = null): List<SubmissionFileEntity> {
        val now = DateParsers.parseInstant(dto.submittedAt)
        return dto.files.map { file ->
            SubmissionFileEntity(
                id = file.id,
                branchId = defaultBranchId,
                submissionId = dto.id,
                storageKey = file.url ?: file.storageKey ?: file.id,
                width = file.width,
                height = file.height,
                bytes = null,
                sha256 = null,
                createdAt = now,
                updatedAt = now,
                deletedAt = null
            )
        }
    }

    fun gradeLevelToBadgeArabic(gradeLevel: String?): String {
        return when (gradeLevel?.lowercase()?.trim()) {
            "excellent" -> "ممتاز"
            "very_good" -> "جيد جدًا"
            "good" -> "جيد"
            "needs_improvement" -> "يحتاج إلى تحسين"
            else -> gradeLevel ?: "غير محدد"
        }
    }

    fun normalizeGradeArabicToAscii(arabicGrade: String?): String? {
        if (arabicGrade == null) return null
        val norm = arabicGrade.trim().replace("داً", "دًا")
        return when (norm) {
            "ممتاز" -> "excellent"
            "جيد جدًا", "جيد جداً" -> "very_good"
            "جيد" -> "good"
            "يحتاج إلى تحسين" -> "needs_improvement"
            else -> null
        }
    }
}
