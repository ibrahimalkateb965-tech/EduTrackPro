package sa.gheras.edutrack.data.repo.mappers

import sa.gheras.edutrack.data.entity.NotificationEntity
import sa.gheras.edutrack.data.remote.dto.NotificationDto
import java.time.Instant

object NotificationMappers {

    fun toEntity(dto: NotificationDto, currentUserId: String = "me", defaultBranchId: String? = null): NotificationEntity {
        val now = Instant.now()
        val created = DateParsers.parseInstant(dto.createdAt)
        return NotificationEntity(
            id = dto.id,
            branchId = defaultBranchId,
            userId = currentUserId,
            kind = "SYSTEM",
            title = dto.title,
            body = dto.body,
            readAt = dto.readAt?.let { DateParsers.parseInstant(it) },
            sentAt = created,
            createdAt = created,
            updatedAt = DateParsers.parseInstant(dto.updatedAt),
            deletedAt = null
        )
    }
}
