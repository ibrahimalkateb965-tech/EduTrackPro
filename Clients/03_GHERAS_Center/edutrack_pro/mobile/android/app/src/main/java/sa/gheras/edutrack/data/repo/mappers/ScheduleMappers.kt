package sa.gheras.edutrack.data.repo.mappers

import sa.gheras.edutrack.data.entity.ScheduleEntity
import sa.gheras.edutrack.data.remote.dto.ScheduleDto

object ScheduleMappers {

    fun toEntity(dto: ScheduleDto): ScheduleEntity {
        return ScheduleEntity(
            id = dto.id,
            branchId = dto.branchId,
            roomId = dto.roomId,
            teacherUserId = dto.teacherUserId,
            day = dto.day,
            startTime = DateParsers.parseLocalTime(dto.startTime),
            endTime = DateParsers.parseLocalTime(dto.endTime),
            subject = dto.subject,
            groupName = dto.groupName,
            createdAt = DateParsers.parseInstant(dto.createdAt),
            updatedAt = DateParsers.parseInstant(dto.updatedAt),
            deletedAt = dto.deletedAt?.let { DateParsers.parseInstant(it) }
        )
    }
}
