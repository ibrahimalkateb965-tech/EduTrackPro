package sa.gheras.edutrack.data.repo.mappers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import sa.gheras.edutrack.data.remote.dto.StudentDto

class StudentMapperTest {

    @Test
    fun testToEntity_mapsFieldsCorrectly() {
        val dto = StudentDto(
            id = "s_1",
            name = "Zaid",
            birthDate = "2015-05-10",
            nationality = "SA",
            gender = "MALE",
            roomId = "r_1",
            roomName = "Halaqa 1",
            groupName = "Morning",
            status = "active",
            hasDifficulties = false,
            difficultyNotes = null,
            childNotes = null,
            guardianPhone = "0555555555",
            guardianRelation = "FATHER"
        )

        val entity = StudentMappers.toEntity(dto, "branch_1")
        assertEquals("s_1", entity.id)
        assertEquals("Zaid", entity.name)
        assertEquals("branch_1", entity.branchId)
        assertEquals("r_1", entity.roomId)
        assertEquals("2015-05-10", entity.birthDate?.toString())
        assertEquals("MALE", entity.gender)
        assertEquals("0555555555", entity.guardianPhone)
    }

    @Test
    fun testSynthesizeRooms_createsDistinctRoomsFromStudents() {
        val students = listOf(
            StudentDto(id = "s_1", name = "Zaid", roomId = "r_1", roomName = "Halaqa A"),
            StudentDto(id = "s_2", name = "Omar", roomId = "r_1", roomName = "Halaqa A"),
            StudentDto(id = "s_3", name = "Ali", roomId = "r_2", roomName = "Halaqa B")
        )

        val rooms = StudentMappers.synthesizeRooms(students, "branch_1")
        assertEquals(2, rooms.size)
        val r1 = rooms.find { it.id == "r_1" }
        assertNotNull(r1)
        assertEquals("Halaqa A", r1?.name)
    }
}
