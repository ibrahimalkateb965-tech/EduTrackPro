package sa.gheras.edutrack.data.repo.mappers

import org.junit.Assert.assertEquals
import org.junit.Test
import sa.gheras.edutrack.data.remote.dto.AttendanceDto
import sa.gheras.edutrack.data.remote.dto.EvaluationDto
import sa.gheras.edutrack.data.remote.dto.LessonLogDto
import sa.gheras.edutrack.data.remote.dto.SkillProgressDto

class AcademicMapperTest {

    @Test
    fun testAttendanceMapping() {
        val dto = AttendanceDto(
            id = "att_1",
            studentId = "s_1",
            scheduleId = "sc_1",
            date = "2026-09-21",
            status = "PRESENT",
            excuseNote = "On time",
            recordedByUserId = "u_1"
        )
        val entity = AcademicMappers.attendanceToEntity(dto, "branch_1")
        assertEquals("att_1", entity.id)
        assertEquals("s_1", entity.studentId)
        assertEquals("2026-09-21", entity.date.toString())
        assertEquals("PRESENT", entity.status)
        assertEquals("On time", entity.note)
    }

    @Test
    fun testEvaluationMapping() {
        val dto = EvaluationDto(
            id = "eval_1",
            studentId = "s_1",
            date = "2026-09-21",
            score = 95.0,
            evalType = "daily",
            notes = "Good"
        )
        val entity = AcademicMappers.evaluationToEntity(dto, "branch_1")
        assertEquals("eval_1", entity.id)
        assertEquals(95.0, entity.value, 0.01)
        assertEquals("daily", entity.evalType)
    }

    @Test
    fun testLessonLogMapping() {
        val dto = LessonLogDto(
            id = "log_1",
            scheduleId = "sc_1",
            date = "2026-09-21",
            status = "تمت",
            covered = "Al-Baqarah 1-10",
            homework = "Memorize 11-15"
        )
        val entity = AcademicMappers.lessonLogToEntity(dto)
        assertEquals("log_1", entity.id)
        assertEquals("sc_1", entity.scheduleId)
        assertEquals("تمت", entity.status)
        assertEquals("Al-Baqarah 1-10", entity.covered)
    }

    @Test
    fun testSkillProgressMapping() {
        val dto = SkillProgressDto(
            id = "sk_1",
            studentId = "s_1",
            subject = "تجويد",
            date = "2026-09-21",
            score = 4.0
        )
        val entity = AcademicMappers.skillProgressToEntity(dto, "branch_1")
        assertEquals("sk_1", entity.id)
        assertEquals("تجويد", entity.subject)
        assertEquals("4.0", entity.level)
    }
}
