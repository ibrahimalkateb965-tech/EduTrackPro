package sa.gheras.edutrack.data.remote

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import sa.gheras.edutrack.data.remote.dto.AttendanceStatsDto
import sa.gheras.edutrack.data.remote.dto.BatchPublishRequest
import sa.gheras.edutrack.data.remote.dto.MonthlyReportDto
import sa.gheras.edutrack.data.remote.dto.ReportPdfResponse
import sa.gheras.edutrack.data.remote.dto.ReviewReportRequest
import sa.gheras.edutrack.data.remote.dto.UpsertMonthlyReportRequest

interface MonthlyReportsApi {

    @GET("monthly-reports/auto-stats")
    suspend fun getAutoStats(
        @Query("student_id") studentId: String,
        @Query("month") month: String
    ): AttendanceStatsDto

    @GET("monthly-reports")
    suspend fun listReports(
        @Query("room_id") roomId: String? = null,
        @Query("month") month: String? = null,
        @Query("status") status: String? = null,
        @Query("student_id") studentId: String? = null
    ): List<MonthlyReportDto>

    @GET("monthly-reports/{id}")
    suspend fun getReport(
        @Path("id") reportId: String
    ): MonthlyReportDto

    @POST("monthly-reports")
    suspend fun upsertReport(
        @Body body: UpsertMonthlyReportRequest
    ): MonthlyReportDto

    @POST("monthly-reports/{id}/review")
    suspend fun reviewReport(
        @Path("id") reportId: String,
        @Body body: ReviewReportRequest
    ): MonthlyReportDto

    @POST("monthly-reports/{id}/publish")
    suspend fun publishReport(
        @Path("id") reportId: String
    ): MonthlyReportDto

    @POST("monthly-reports/batch-publish")
    suspend fun batchPublish(
        @Body body: BatchPublishRequest
    ): List<MonthlyReportDto>

    @GET("monthly-reports/{id}/pdf")
    suspend fun getReportPdf(
        @Path("id") reportId: String
    ): ReportPdfResponse

    @GET("me/monthly-reports")
    suspend fun listMyMonthlyReports(
        @Query("student_id") studentId: String,
        @Query("month") month: String? = null
    ): List<MonthlyReportDto>

    @GET("me/monthly-reports/{id}")
    suspend fun getMyMonthlyReport(
        @Path("id") reportId: String
    ): MonthlyReportDto
}
