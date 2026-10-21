package sa.gheras.edutrack.data.repo.mappers

import sa.gheras.edutrack.data.entity.PickupDelegateEntity
import sa.gheras.edutrack.data.remote.dto.PickupDelegateDto
import java.time.Instant

object PickupMappers {

    fun delegateToEntity(dto: PickupDelegateDto): PickupDelegateEntity {
        return PickupDelegateEntity(
            id = dto.id,
            studentId = dto.studentId,
            name = dto.name,
            phone = dto.phone,
            relation = dto.relation,
            isApproved = dto.isApproved,
            clientRequestId = dto.clientRequestId,
            nationalIdMasked = dto.nationalIdMasked,
            createdAt = dto.createdAt?.let { DateParsers.parseInstant(it) } ?: Instant.now(),
            updatedAt = dto.updatedAt?.let { DateParsers.parseInstant(it) } ?: Instant.now(),
            deletedAt = null
        )
    }

    fun delegateToDto(entity: PickupDelegateEntity): PickupDelegateDto {
        return PickupDelegateDto(
            id = entity.id,
            studentId = entity.studentId,
            name = entity.name,
            phone = entity.phone,
            relation = entity.relation,
            isApproved = entity.isApproved,
            clientRequestId = entity.clientRequestId,
            nationalIdMasked = entity.nationalIdMasked,
            createdAt = entity.createdAt?.toString(),
            updatedAt = entity.updatedAt?.toString()
        )
    }
}
