package sa.gheras.edutrack.data.repo.mappers

import sa.gheras.edutrack.data.entity.NotificationEntity
import sa.gheras.edutrack.data.remote.dto.NotificationDto
import java.time.Instant

object NotificationMappers {

    fun toEntity(dto: NotificationDto, currentUserId: String = "me", defaultBranchId: String? = null): NotificationEntity {
        val created = DateParsers.parseInstant(dto.createdAt)
        val sent = dto.sentAt?.let { DateParsers.parseInstant(it) } ?: created
        val validPriority = if (dto.priority.equals("urgent", ignoreCase = true)) "urgent" else "normal"
        val resolvedBranchId = dto.branchId ?: defaultBranchId

        return NotificationEntity(
            id = dto.id,
            branchId = resolvedBranchId,
            userId = currentUserId,
            kind = dto.kind,
            title = dto.title,
            body = dto.body,
            targetType = dto.targetType,
            targetId = dto.targetId,
            priority = validPriority,
            actionUrl = dto.actionUrl,
            senderUserId = dto.senderUserId,
            senderName = dto.senderName,
            broadcastId = dto.broadcastId,
            readAt = dto.readAt?.let { DateParsers.parseInstant(it) },
            sentAt = sent,
            createdAt = created,
            updatedAt = DateParsers.parseInstant(dto.updatedAt),
            deletedAt = null
        )
    }
}
