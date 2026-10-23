package sa.gheras.edutrack.data.repo

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import sa.gheras.edutrack.data.dao.MonthlyReportDao
import sa.gheras.edutrack.data.entity.MonthlyStudentReportEntity
import sa.gheras.edutrack.data.remote.MonthlyReportsApi
import sa.gheras.edutrack.data.remote.dto.AttendanceStatsDto
import sa.gheras.edutrack.data.remote.dto.BatchPublishRequest
import sa.gheras.edutrack.data.remote.dto.MonthlyReportDto
import sa.gheras.edutrack.data.remote.dto.ReportPdfResponse
import sa.gheras.edutrack.data.remote.dto.ReviewReportRequest
import sa.gheras.edutrack.data.remote.dto.UpsertMonthlyReportRequest
import java.time.Instant

class MonthlyReportsRepository(
    private val monthlyReportDao: MonthlyReportDao,
    private val monthlyReportsApi: MonthlyReportsApi,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun observeReportById(id: String): Flow<MonthlyStudentReportEntity?> {
        return monthlyReportDao.observeReportById(id)
    }

    fun observeReportsByStudent(studentId: String): Flow<List<MonthlyStudentReportEntity>> {
        return monthlyReportDao.observeReportsByStudent(studentId)
    }

    fun observeReportByStudentAndMonth(studentId: String, month: String): Flow<MonthlyStudentReportEntity?> {
        return monthlyReportDao.observeReportByStudentAndMonth(studentId, month)
    }

    fun observeReportsByRoomAndMonth(roomId: String, month: String): Flow<List<MonthlyStudentReportEntity>> {
        return monthlyReportDao.observeReportsByRoomAndMonth(roomId, month)
    }

    fun observePendingReportsByRoom(roomId: String): Flow<List<MonthlyStudentReportEntity>> {
        return monthlyReportDao.observePendingReportsByRoom(roomId)
    }

    fun observeAllPendingReports(): Flow<List<MonthlyStudentReportEntity>> {
        return monthlyReportDao.observeAllPendingReports()
    }

    suspend fun fetchAutoStats(
        studentId: String,
        month: String
    ): Result<AttendanceStatsDto> = withContext(ioDispatcher) {
        runCatching {
            monthlyReportsApi.getAutoStats(studentId = studentId, month = month)
        }
    }

    suspend fun refreshStaffReports(
        roomId: String? = null,
        month: String? = null,
        status: String? = null,
        studentId: String? = null
    ): Result<List<MonthlyReportDto>> = withContext(ioDispatcher) {
        runCatching {
            val dtoList = monthlyReportsApi.listReports(
                roomId = roomId,
                month = month,
                status = status,
                studentId = studentId
            )
            val entities = dtoList.map { it.toEntity() }
            monthlyReportDao.upsertReports(entities)
            dtoList
        }
    }

    suspend fun fetchReportById(reportId: String): Result<MonthlyReportDto> = withContext(ioDispatcher) {
        runCatching {
            val dto = monthlyReportsApi.getReport(reportId)
            monthlyReportDao.upsertReport(dto.toEntity())
            dto
        }
    }

    suspend fun upsertReport(request: UpsertMonthlyReportRequest): Result<MonthlyReportDto> = withContext(ioDispatcher) {
        runCatching {
            val dto = monthlyReportsApi.upsertReport(request)
            monthlyReportDao.upsertReport(dto.toEntity())
            dto
        }
    }

    suspend fun reviewReport(
        reportId: String,
        request: ReviewReportRequest
    ): Result<MonthlyReportDto> = withContext(ioDispatcher) {
        runCatching {
            val dto = monthlyReportsApi.reviewReport(reportId, request)
            monthlyReportDao.upsertReport(dto.toEntity())
            dto
        }
    }

    suspend fun publishReport(reportId: String): Result<MonthlyReportDto> = withContext(ioDispatcher) {
        runCatching {
            val dto = monthlyReportsApi.publishReport(reportId)
            monthlyReportDao.upsertReport(dto.toEntity())
            dto
        }
    }

    suspend fun batchPublish(
        roomId: String,
        month: String
    ): Result<List<MonthlyReportDto>> = withContext(ioDispatcher) {
        runCatching {
            val dtoList = monthlyReportsApi.batchPublish(BatchPublishRequest(roomId = roomId, month = month))
            val entities = dtoList.map { it.toEntity() }
            monthlyReportDao.upsertReports(entities)
            dtoList
        }
    }

    suspend fun refreshGuardianReports(
        studentId: String,
        month: String? = null
    ): Result<List<MonthlyReportDto>> = withContext(ioDispatcher) {
        runCatching {
            val dtoList = monthlyReportsApi.listMyMonthlyReports(studentId = studentId, month = month)
            val entities = dtoList.map { it.toEntity() }
            monthlyReportDao.upsertReports(entities)
            dtoList
        }
    }

    suspend fun fetchReportPdf(reportId: String): Result<ReportPdfResponse> = withContext(ioDispatcher) {
        runCatching {
            monthlyReportsApi.getReportPdf(reportId)
        }
    }

    private fun MonthlyReportDto.toEntity(): MonthlyStudentReportEntity = MonthlyStudentReportEntity(
        id = id,
        branchId = branchId,
        studentId = studentId,
        roomId = roomId,
        month = month,
        teacherUserId = teacherUserId ?: "",
        status = status,
        attendanceStatsJson = json.encodeToString(attendanceStats ?: AttendanceStatsDto()),
        subjectRatingsJson = json.encodeToString(subjectRatings),
        strengths = strengths,
        improvements = improvements,
        recommendations = recommendations,
        revisionNotes = revisionNotes,
        submittedAt = submittedAt?.let(Instant::parse),
        reviewedByUserId = reviewedByUserId,
        reviewedAt = reviewedAt?.let(Instant::parse),
        publishedAt = publishedAt?.let(Instant::parse),
        createdAt = createdAt?.let(Instant::parse) ?: Instant.now(),
        updatedAt = updatedAt?.let(Instant::parse) ?: Instant.now()
    )
}
