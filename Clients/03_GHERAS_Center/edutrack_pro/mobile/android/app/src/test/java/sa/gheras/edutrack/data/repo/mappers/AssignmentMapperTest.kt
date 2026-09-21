package sa.gheras.edutrack.data.repo.mappers

import org.junit.Assert.assertEquals
import org.junit.Test
import sa.gheras.edutrack.data.remote.dto.AssignmentDto
import sa.gheras.edutrack.data.remote.dto.SubmissionDto
import sa.gheras.edutrack.data.remote.dto.SubmissionFileDto

class AssignmentMapperTest {

    @Test
    fun testAssignmentMapping() {
        val dto = AssignmentDto(
            id = "asgn_1",
            scheduleId = "sc_1",
            title = "Surah Memorization",
            dueDate = "2026-09-28",
            studentIds = listOf("s_1", "s_2")
        )
        val entity = AssignmentMappers.assignmentToEntity(dto, "branch_1")
        assertEquals("asgn_1", entity.id)
        assertEquals("Surah Memorization", entity.title)
        assertEquals("2026-09-28", entity.dueDate.toString())

        val students = AssignmentMappers.assignmentStudentsToEntities(dto, "branch_1")
        assertEquals(2, students.size)
        assertEquals("asgn_1:s_1", students[0].id)
        assertEquals("s_1", students[0].studentId)
    }

    @Test
    fun testSubmissionMapping() {
        val dto = SubmissionDto(
            id = "sub_1",
            assignmentId = "asgn_1",
            studentId = "s_1",
            status = "submitted",
            submittedAt = "2026-09-21T08:00:00Z",
            files = listOf(SubmissionFileDto(id = "f_1", storageKey = "uploads/audio.mp3"))
        )
        val entity = AssignmentMappers.submissionToEntity(dto, "branch_1")
        assertEquals("sub_1", entity.id)
        assertEquals("asgn_1", entity.assignmentId)
        assertEquals("submitted", entity.status)

        val files = AssignmentMappers.submissionFilesToEntities(dto, "branch_1")
        assertEquals(1, files.size)
        assertEquals("f_1", files[0].id)
        assertEquals("uploads/audio.mp3", files[0].storageKey)
    }
}
