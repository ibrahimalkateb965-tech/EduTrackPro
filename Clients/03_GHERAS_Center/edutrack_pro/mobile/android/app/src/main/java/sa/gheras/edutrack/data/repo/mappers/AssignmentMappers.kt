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
            subject = null,
            kind = "HOMEWORK",
            dueDate = DateParsers.parseLocalDate(dto.dueDate) ?: LocalDate.now(),
            teacherUserId = dto.teacherUserId,
            instructions = dto.description,
            pageRef = null,
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
            submittedAt = DateParsers.parseInstant(dto.submittedAt),
            status = dto.status ?: "submitted",
            grade = null,
            feedback = null,
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
                storageKey = file.storageKey ?: file.id,
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
}
