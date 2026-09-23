package sa.gheras.edutrack.ui.teacher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class TeacherHomeViewModelTest {

    @Test
    fun `saturday is a school day, only friday is weekend`() {
        assertFalse(TeacherHomeViewModel.isWeekend(DayOfWeek.SATURDAY))
        assertTrue(TeacherHomeViewModel.isWeekend(DayOfWeek.FRIDAY))
        listOf(
            DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY
        ).forEach { assertFalse(it.name, TeacherHomeViewModel.isWeekend(it)) }
    }

    @Test
    fun `school week starts on saturday and spans six days`() {
        // 2026-09-23 is a Wednesday → week began Saturday 2026-09-19.
        val week = TeacherHomeViewModel.schoolWeek(LocalDate.parse("2026-09-23"))

        assertEquals(
            listOf("السبت", "الأحد", "الاثنين", "الثلاثاء", "الأربعاء", "الخميس"),
            week.map { it.first }
        )
        assertEquals(LocalDate.parse("2026-09-19"), week.first().second)
        assertEquals(LocalDate.parse("2026-09-24"), week.last().second)
    }

    @Test
    fun `every day of the week resolves to the same saturday-anchored week`() {
        val saturday = LocalDate.parse("2026-09-19")
        (0L..6L).forEach { offset ->
            val today = saturday.plusDays(offset)
            val week = TeacherHomeViewModel.schoolWeek(today)
            assertEquals(today.toString(), saturday, week.first().second)
            assertEquals(DayOfWeek.SATURDAY, week.first().second.dayOfWeek)
        }
    }
}
