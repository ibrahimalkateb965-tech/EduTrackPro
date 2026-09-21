package sa.gheras.edutrack.data.repo.mappers

import sa.gheras.edutrack.data.entity.RoomEntity
import sa.gheras.edutrack.data.entity.StudentEntity
import sa.gheras.edutrack.data.remote.dto.RoomDto
import sa.gheras.edutrack.data.remote.dto.StudentDto
import java.time.Instant

object StudentMappers {

    fun toEntity(dto: StudentDto, defaultBranchId: String? = null): StudentEntity {
        val now = Instant.now()
        return StudentEntity(
            id = dto.id,
            branchId = defaultBranchId,
            name = dto.name,
            nationalId = null,
            birthDate = DateParsers.parseLocalDate(dto.birthDate),
            nationality = dto.nationality,
            gender = dto.gender,
            hasDifficulties = dto.hasDifficulties ?: false,
            difficultyNotes = dto.difficultyNotes,
            childNotes = dto.childNotes,
            fatherName = null,
            fatherPhone = null,
            motherName = null,
            motherPhone = null,
            guardianPhone = dto.guardianPhone,
            guardianRelation = dto.guardianRelation,
            pickupType = null,
            pickupName = null,
            pickupRelation = null,
            pickupPhone = null,
            previousStudy = false,
            previousSchool = null,
            previousLevel = null,
            educationNotes = null,
            roomId = dto.roomId,
            groupName = dto.groupName,
            status = dto.status ?: "active",
            createdAt = now,
            updatedAt = now,
            deletedAt = null
        )
    }

    /**
     * In guardian scope, GET /me/rooms is forbidden (teacher-only).
     * We synthesize rooms from students' room_id + room_name so RoomEntity foreign keys hold.
     */
    fun synthesizeRooms(students: List<StudentDto>, defaultBranchId: String? = null): List<RoomEntity> {
        val now = Instant.now()
        return students
            .filter { it.roomId != null }
            .distinctBy { it.roomId }
            .map { s ->
                RoomEntity(
                    id = s.roomId!!,
                    branchId = defaultBranchId,
                    name = s.roomName ?: "حلقة ${s.name}",
                    groupName = s.groupName ?: "",
                    createdAt = now,
                    updatedAt = now,
                    deletedAt = null
                )
            }
    }

    fun roomToEntity(dto: RoomDto): RoomEntity {
        return RoomEntity(
            id = dto.id,
            branchId = dto.branchId,
            name = dto.name,
            groupName = dto.groupName,
            createdAt = DateParsers.parseInstant(dto.createdAt),
            updatedAt = DateParsers.parseInstant(dto.updatedAt),
            deletedAt = dto.deletedAt?.let { DateParsers.parseInstant(it) }
        )
    }
}
