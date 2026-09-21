package sa.gheras.edutrack.data.repo

import kotlinx.coroutines.flow.Flow
import sa.gheras.edutrack.data.dao.AssignmentDao
import sa.gheras.edutrack.data.dao.SubmissionDao
import sa.gheras.edutrack.data.entity.AssignmentEntity
import sa.gheras.edutrack.data.entity.SubmissionEntity

class AssignmentsRepository(
    private val assignmentDao: AssignmentDao,
    private val submissionDao: SubmissionDao
) {
    fun observeAll(): Flow<List<AssignmentEntity>> = assignmentDao.observeAll()

    fun observeByStudent(studentId: String): Flow<List<AssignmentEntity>> =
        assignmentDao.observeByStudent(studentId)

    suspend fun getById(id: String): AssignmentEntity? = assignmentDao.getById(id)

    fun observeSubmissions(assignmentId: String): Flow<List<SubmissionEntity>> =
        submissionDao.observeByAssignment(assignmentId)

    fun observeStudentSubmissions(studentId: String): Flow<List<SubmissionEntity>> =
        submissionDao.observeByStudent(studentId)
}
