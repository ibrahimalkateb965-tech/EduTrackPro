package sa.gheras.edutrack.data.remote

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import sa.gheras.edutrack.data.remote.dto.CreateThreadRequest
import sa.gheras.edutrack.data.remote.dto.DeliveredResponse
import sa.gheras.edutrack.data.remote.dto.ReadResponse
import sa.gheras.edutrack.data.remote.dto.SendMessageRequest
import sa.gheras.edutrack.data.remote.dto.ThreadDto
import sa.gheras.edutrack.data.remote.dto.ThreadMessageDto
import sa.gheras.edutrack.data.remote.dto.DailySummaryDto
import sa.gheras.edutrack.data.remote.dto.UpsertDailySummaryRequest

interface ThreadsApi {

    @GET("threads")
    suspend fun listThreads(
        @Query("student_id") studentId: String? = null
    ): List<ThreadDto>

    @POST("threads")
    suspend fun createThread(
        @Body body: CreateThreadRequest
    ): ThreadDto

    @GET("threads/{id}")
    suspend fun getThread(
        @Path("id") threadId: String
    ): ThreadDto

    @GET("threads/{id}/messages")
    suspend fun listMessages(
        @Path("id") threadId: String,
        @Query("limit") limit: Int = 100
    ): List<ThreadMessageDto>

    @POST("threads/{id}/messages")
    suspend fun sendMessage(
        @Path("id") threadId: String,
        @Body body: SendMessageRequest
    ): ThreadMessageDto

    @POST("threads/{id}/messages/delivered")
    suspend fun markDelivered(
        @Path("id") threadId: String
    ): DeliveredResponse

    @POST("threads/{id}/messages/read")
    suspend fun markRead(
        @Path("id") threadId: String
    ): ReadResponse

    // Staff Daily Summaries
    @GET("daily-summaries")
    suspend fun listDailySummaries(
        @Query("room_id") roomId: String? = null,
        @Query("summary_date") summaryDate: String? = null,
        @Query("is_published") isPublished: Boolean? = null
    ): List<DailySummaryDto>

    @GET("daily-summaries/{id}")
    suspend fun getDailySummary(
        @Path("id") summaryId: String
    ): DailySummaryDto

    @POST("daily-summaries")
    suspend fun upsertDailySummary(
        @Body body: UpsertDailySummaryRequest
    ): DailySummaryDto

    @POST("daily-summaries/{id}/publish")
    suspend fun publishDailySummary(
        @Path("id") summaryId: String
    ): DailySummaryDto

    // Guardian Daily Summaries
    @GET("me/daily-summaries")
    suspend fun listGuardianDailySummaries(
        @Query("student_id") studentId: String,
        @Query("summary_date") summaryDate: String? = null
    ): List<DailySummaryDto>
}
