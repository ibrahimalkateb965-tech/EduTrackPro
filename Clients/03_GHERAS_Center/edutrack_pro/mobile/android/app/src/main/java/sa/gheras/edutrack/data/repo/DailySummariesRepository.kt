package sa.gheras.edutrack.data.repo

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import sa.gheras.edutrack.data.dao.DailySummaryDao
import sa.gheras.edutrack.data.entity.DailySummaryEntity
import sa.gheras.edutrack.data.entity.DailySummaryNoteEntity
import sa.gheras.edutrack.data.remote.ThreadsApi
import sa.gheras.edutrack.data.remote.dto.DailySummaryDto
import sa.gheras.edutrack.data.remote.dto.StudentNoteDto
import sa.gheras.edutrack.data.remote.dto.UpsertDailySummaryRequest
import java.time.Instant
import java.time.LocalDate

class DailySummariesRepository(
    private val dailySummaryDao: DailySummaryDao,
    private val threadsApi: ThreadsApi,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    fun observeSummariesByRoom(roomId: String): Flow<List<DailySummaryEntity>> {
        return dailySummaryDao.observeSummariesByRoom(roomId)
    }

    fun observeSummariesByRoomAndDate(roomId: String, date: LocalDate): Flow<List<DailySummaryEntity>> {
        return dailySummaryDao.observeSummariesByRoomAndDate(roomId, date)
    }

    fun observeStudentNote(studentId: String, summaryId: String): Flow<DailySummaryNoteEntity?> {
        return dailySummaryDao.observeNoteByStudentAndSummary(studentId, summaryId)
    }

    suspend fun refreshStaffSummaries(
        roomId: String,
        summaryDate: String? = null
    ): Result<List<DailySummaryDto>> = withContext(ioDispatcher) {
        runCatching {
            val dtoList = threadsApi.listDailySummaries(roomId = roomId, summaryDate = summaryDate)
            val summaries = dtoList.map { it.toEntity() }
            val notes = dtoList.flatMap { summaryDto ->
                summaryDto.studentNotes.map { noteDto ->
                    noteDto.toEntity(summaryDto.id, summaryDto.branchId)
                }
            }
            dailySummaryDao.replaceScope(roomId, summaries, notes)
            dtoList
        }
    }

    suspend fun refreshGuardianSummaries(
        studentId: String,
        summaryDate: String? = null
    ): Result<List<DailySummaryDto>> = withContext(ioDispatcher) {
        runCatching {
            val dtoList = threadsApi.listGuardianDailySummaries(studentId = studentId, summaryDate = summaryDate)
            val summaries = dtoList.map { it.toEntity() }
            val notes = dtoList.mapNotNull { summaryDto ->
                summaryDto.studentPrivateNote?.let { noteText ->
                    DailySummaryNoteEntity(
                        id = "${summaryDto.id}_$studentId",
                        branchId = summaryDto.branchId,
                        summaryId = summaryDto.id,
                        studentId = studentId,
                        privateNote = noteText,
                        createdAt = Instant.now(),
                        updatedAt = Instant.now()
                    )
                }
            }
            dailySummaryDao.upsertSummaries(summaries)
            dailySummaryDao.upsertNotes(notes)
            dtoList
        }
    }

    suspend fun upsertDailySummary(request: UpsertDailySummaryRequest): Result<DailySummaryDto> = withContext(ioDispatcher) {
        runCatching {
            val summaryDto = threadsApi.upsertDailySummary(request)
            dailySummaryDao.upsertSummary(summaryDto.toEntity())
            val notes = summaryDto.studentNotes.map { it.toEntity(summaryDto.id, summaryDto.branchId) }
            dailySummaryDao.upsertNotes(notes)
            summaryDto
        }
    }

    suspend fun publishSummary(summaryId: String): Result<DailySummaryDto> = withContext(ioDispatcher) {
        runCatching {
            val summaryDto = threadsApi.publishDailySummary(summaryId)
            dailySummaryDao.upsertSummary(summaryDto.toEntity())
            summaryDto
        }
    }

    private fun DailySummaryDto.toEntity(): DailySummaryEntity = DailySummaryEntity(
        id = id,
        branchId = branchId,
        roomId = roomId,
        teacherUserId = teacherUserId,
        summaryDate = LocalDate.parse(summaryDate),
        subject = subject,
        title = title,
        classSummary = classSummary,
        homeworkSummary = homeworkSummary,
        isPublished = isPublished,
        publishedAt = publishedAt?.let(Instant::parse),
        createdAt = createdAt?.let(Instant::parse) ?: Instant.now(),
        updatedAt = updatedAt?.let(Instant::parse) ?: Instant.now()
    )

    private fun StudentNoteDto.toEntity(summaryId: String, branchId: String?): DailySummaryNoteEntity = DailySummaryNoteEntity(
        id = id ?: "${summaryId}_$studentId",
        branchId = branchId,
        summaryId = summaryId,
        studentId = studentId,
        privateNote = privateNote,
        createdAt = Instant.now(),
        updatedAt = Instant.now()
    )
}
