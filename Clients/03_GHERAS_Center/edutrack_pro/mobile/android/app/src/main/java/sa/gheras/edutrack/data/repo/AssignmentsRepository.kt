package sa.gheras.edutrack.data.repo

import kotlinx.coroutines.flow.Flow
import sa.gheras.edutrack.data.dao.AssignmentDao
import sa.gheras.edutrack.data.dao.SubmissionDao
import sa.gheras.edutrack.data.entity.AssignmentEntity
import sa.gheras.edutrack.data.entity.SubmissionEntity

import sa.gheras.edutrack.data.dao.AssignmentStudentDao
import org.json.JSONArray
import org.json.JSONObject
import sa.gheras.edutrack.data.entity.AssignmentStudentEntity
import sa.gheras.edutrack.data.entity.PendingWriteEntity
import sa.gheras.edutrack.sync.Outbox
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class AssignmentsRepository(
    private val assignmentDao: AssignmentDao,
    private val submissionDao: SubmissionDao,
    private val assignmentStudentDao: AssignmentStudentDao? = null,
    private val outbox: Outbox? = null
) {
    fun observeAll(): Flow<List<AssignmentEntity>> = assignmentDao.observeAll()

    fun observeByStudent(studentId: String): Flow<List<AssignmentEntity>> =
        assignmentDao.observeByStudent(studentId)

    suspend fun getById(id: String): AssignmentEntity? = assignmentDao.getById(id)

    fun observeSubmissions(assignmentId: String): Flow<List<SubmissionEntity>> =
        submissionDao.observeByAssignment(assignmentId)

    fun observeStudentSubmissions(studentId: String): Flow<List<SubmissionEntity>> =
        submissionDao.observeByStudent(studentId)

    suspend fun createAssignment(
        title: String,
        subject: String?,
        dueDate: LocalDate,
        instructions: String?,
        pageRef: String?,
        teacherUserId: String?,
        studentIds: List<String>,
        branchId: String? = null
    ): AssignmentEntity {
        val now = Instant.now()
        val assignmentId = UUID.randomUUID().toString()
        val assignment = AssignmentEntity(
            id = assignmentId,
            branchId = branchId,
            title = title,
            subject = subject,
            kind = "homework",
            dueDate = dueDate,
            teacherUserId = teacherUserId,
            instructions = instructions,
            pageRef = pageRef,
            createdAt = now,
            updatedAt = now,
            deletedAt = null
        )
        if (outbox != null) {
            val payload = JSONObject().apply {
                put("assignment_id", assignmentId)
                put("title", title)
                if (subject != null) put("subject", subject)
                put("due_date", dueDate.toString())
                if (instructions != null) put("instructions", instructions)
                if (pageRef != null) put("page_ref", pageRef)
                if (teacherUserId != null) put("teacher_user_id", teacherUserId)
                if (branchId != null) put("branch_id", branchId)
                val arr = JSONArray()
                studentIds.forEach { arr.put(it) }
                put("student_ids", arr)
            }.toString()

            val naturalKey = "assignment:$assignmentId"
            outbox.enqueue(
                kind = PendingWriteEntity.KIND_ASSIGNMENT,
                naturalKey = naturalKey,
                payloadJson = payload
            )
        } else {
            assignmentDao.upsert(assignment)
            if (studentIds.isNotEmpty() && assignmentStudentDao != null) {
                val links = studentIds.map { studentId ->
                    AssignmentStudentEntity(
                        id = UUID.randomUUID().toString(),
                        branchId = branchId,
                        assignmentId = assignmentId,
                        studentId = studentId,
                        createdAt = now,
                        updatedAt = now,
                        deletedAt = null
                    )
                }
                assignmentStudentDao.upsertAll(links)
            }
        }
        return assignment
    }
}
