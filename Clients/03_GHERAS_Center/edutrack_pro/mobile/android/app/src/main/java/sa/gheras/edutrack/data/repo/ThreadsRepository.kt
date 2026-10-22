package sa.gheras.edutrack.data.repo

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import sa.gheras.edutrack.data.dao.ThreadDao
import sa.gheras.edutrack.data.entity.ThreadEntity
import sa.gheras.edutrack.data.entity.ThreadMessageEntity
import sa.gheras.edutrack.data.remote.ThreadsApi
import sa.gheras.edutrack.data.remote.dto.CreateThreadRequest
import sa.gheras.edutrack.data.remote.dto.SendMessageRequest
import sa.gheras.edutrack.data.remote.dto.ThreadDto
import sa.gheras.edutrack.data.remote.dto.ThreadMessageDto
import java.time.Instant

class ThreadsRepository(
    private val threadDao: ThreadDao,
    private val threadsApi: ThreadsApi,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    fun observeThreads(studentId: String? = null): Flow<List<ThreadEntity>> {
        return if (studentId != null) {
            threadDao.observeThreadsByStudent(studentId)
        } else {
            threadDao.observeThreads()
        }
    }

    fun observeMessages(threadId: String): Flow<List<ThreadMessageEntity>> {
        return threadDao.observeMessagesByThread(threadId)
    }

    suspend fun refreshThreads(studentId: String? = null): Result<List<ThreadDto>> = withContext(ioDispatcher) {
        runCatching {
            val dtoList = threadsApi.listThreads(studentId)
            val entities = dtoList.map { it.toEntity() }
            threadDao.upsertThreads(entities)
            dtoList
        }
    }

    suspend fun refreshMessages(threadId: String): Result<List<ThreadMessageDto>> = withContext(ioDispatcher) {
        runCatching {
            val dtoList = threadsApi.listMessages(threadId)
            val entities = dtoList.map { it.toEntity() }
            threadDao.replaceThreadMessages(threadId, entities)
            dtoList
        }
    }

    suspend fun createThread(request: CreateThreadRequest): Result<ThreadDto> = withContext(ioDispatcher) {
        runCatching {
            val threadDto = threadsApi.createThread(request)
            threadDao.upsertThread(threadDto.toEntity())
            threadDto
        }
    }

    suspend fun sendMessage(
        threadId: String,
        body: String,
        attachmentStorageKey: String? = null,
        attachmentType: String? = null,
        attachmentName: String? = null,
        attachmentSizeBytes: Long? = null,
        clientMessageId: String? = null
    ): Result<ThreadMessageDto> = withContext(ioDispatcher) {
        runCatching {
            val req = SendMessageRequest(
                body = body,
                attachmentStorageKey = attachmentStorageKey,
                attachmentType = attachmentType,
                attachmentName = attachmentName,
                attachmentSizeBytes = attachmentSizeBytes,
                clientMessageId = clientMessageId
            )
            val msgDto = threadsApi.sendMessage(threadId, req)
            threadDao.upsertMessage(msgDto.toEntity())
            msgDto
        }
    }

    suspend fun markDelivered(threadId: String): Result<Int> = withContext(ioDispatcher) {
        runCatching {
            val res = threadsApi.markDelivered(threadId)
            res.deliveredCount
        }
    }

    suspend fun markRead(threadId: String): Result<Int> = withContext(ioDispatcher) {
        runCatching {
            val res = threadsApi.markRead(threadId)
            res.readCount
        }
    }

    private fun ThreadDto.toEntity(): ThreadEntity = ThreadEntity(
        id = id,
        branchId = branchId,
        studentId = studentId,
        guardianUserId = guardianUserId,
        staffUserId = staffUserId,
        subject = subject,
        linkedAssignmentId = linkedAssignmentId,
        linkedSummaryId = linkedSummaryId,
        status = status,
        lastMessageAt = Instant.parse(lastMessageAt),
        lastMessagePreview = lastMessagePreview,
        createdAt = createdAt?.let(Instant::parse) ?: Instant.now(),
        updatedAt = updatedAt?.let(Instant::parse) ?: Instant.now()
    )

    private fun ThreadMessageDto.toEntity(): ThreadMessageEntity = ThreadMessageEntity(
        id = id,
        branchId = branchId,
        threadId = threadId,
        senderUserId = senderUserId,
        senderRole = senderRole,
        body = body,
        attachmentStorageKey = attachmentStorageKey,
        attachmentType = attachmentType,
        attachmentName = attachmentName,
        attachmentSizeBytes = attachmentSizeBytes,
        sentAt = Instant.parse(sentAt),
        deliveredAt = deliveredAt?.let(Instant::parse),
        readAt = readAt?.let(Instant::parse),
        clientMessageId = clientMessageId,
        createdAt = createdAt?.let(Instant::parse) ?: Instant.now(),
        updatedAt = updatedAt?.let(Instant::parse) ?: Instant.now()
    )
}
