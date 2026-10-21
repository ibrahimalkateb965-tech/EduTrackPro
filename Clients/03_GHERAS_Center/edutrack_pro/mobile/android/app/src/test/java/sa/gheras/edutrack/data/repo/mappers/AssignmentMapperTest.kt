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
            subject = "القرآن",
            instructions = "حفظ الآيات 1 إلى 10",
            pageRef = "صفحة 582",
            dueDate = "2026-09-28",
            studentIds = listOf("s_1", "s_2")
        )
        val entity = AssignmentMappers.assignmentToEntity(dto, "branch_1")
        assertEquals("asgn_1", entity.id)
        assertEquals("Surah Memorization", entity.title)
        assertEquals("القرآن", entity.subject)
        assertEquals("حفظ الآيات 1 إلى 10", entity.instructions)
        assertEquals("صفحة 582", entity.pageRef)
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
            attemptNumber = 2,
            parentSubmissionId = "sub_0",
            gradeLevel = "EXCELLENT",
            revisionNote = "Fix question 2",
            teacherFeedbackKey = "uploads/voice.mp3",
            isLate = true,
            status = "submitted",
            submittedAt = "2026-09-21T08:00:00Z",
            files = listOf(SubmissionFileDto(id = "f_1", storageKey = "uploads/audio.mp3"))
        )
        val entity = AssignmentMappers.submissionToEntity(dto, "branch_1")
        assertEquals("sub_1", entity.id)
        assertEquals("asgn_1", entity.assignmentId)
        assertEquals("submitted", entity.status)
        assertEquals(2, entity.attemptNumber)
        assertEquals("sub_0", entity.parentSubmissionId)
        assertEquals("EXCELLENT", entity.gradeLevel)
        assertEquals("Fix question 2", entity.revisionNote)
        assertEquals("uploads/voice.mp3", entity.teacherFeedbackKey)
        assertEquals(true, entity.isLate)

        val files = AssignmentMappers.submissionFilesToEntities(dto, "branch_1")
        assertEquals(1, files.size)
        assertEquals("f_1", files[0].id)
        assertEquals("uploads/audio.mp3", files[0].storageKey)
    }

    @Test
    fun testNullableDueDateAndDraftMapping() {
        val dto = AssignmentDto(
            id = "asgn_null_due",
            title = "Open Assignment",
            dueDate = null,
            isDraft = true
        )
        val entity = AssignmentMappers.assignmentToEntity(dto)
        assertEquals(null, entity.dueDate)
        assertEquals(true, entity.isDraft)
    }

    @Test
    fun testGradeLevelArabicMapping() {
        assertEquals("ممتاز", AssignmentMappers.gradeLevelToBadgeArabic("excellent"))
        assertEquals("جيد جدًا", AssignmentMappers.gradeLevelToBadgeArabic("very_good"))
        assertEquals("جيد", AssignmentMappers.gradeLevelToBadgeArabic("good"))
        assertEquals("يحتاج إلى تحسين", AssignmentMappers.gradeLevelToBadgeArabic("needs_improvement"))
        assertEquals("غير محدد", AssignmentMappers.gradeLevelToBadgeArabic(null))
    }

    @Test
    fun testNormalizeGradeArabicToAscii() {
        assertEquals("excellent", AssignmentMappers.normalizeGradeArabicToAscii("ممتاز"))
        assertEquals("very_good", AssignmentMappers.normalizeGradeArabicToAscii("جيد جدًا"))
        assertEquals("very_good", AssignmentMappers.normalizeGradeArabicToAscii("جيد جداً")) // alternate tanween
        assertEquals("good", AssignmentMappers.normalizeGradeArabicToAscii("جيد"))
        assertEquals("needs_improvement", AssignmentMappers.normalizeGradeArabicToAscii("يحتاج إلى تحسين"))
    }
}
