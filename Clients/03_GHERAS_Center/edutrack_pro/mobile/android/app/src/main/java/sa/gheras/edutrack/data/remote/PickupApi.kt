package sa.gheras.edutrack.data.remote

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import sa.gheras.edutrack.data.remote.dto.ActivePickupRequestsResponse
import sa.gheras.edutrack.data.remote.dto.CancelPickupRequest
import sa.gheras.edutrack.data.remote.dto.CreateDelegateRequest
import sa.gheras.edutrack.data.remote.dto.CreatePickupRequest
import sa.gheras.edutrack.data.remote.dto.FailPickupRequest
import sa.gheras.edutrack.data.remote.dto.HandoverRequest
import sa.gheras.edutrack.data.remote.dto.PickupDelegateDto
import sa.gheras.edutrack.data.remote.dto.PickupRequestDto

interface PickupApi {

    @GET("pickup/requests/active")
    suspend fun getActiveRequests(): ActivePickupRequestsResponse

    @GET("pickup/requests/{id}")
    suspend fun getRequestById(@Path("id") id: String): PickupRequestDto

    @POST("pickup/delegates")
    suspend fun createDelegate(@Body body: CreateDelegateRequest): PickupDelegateDto

    @DELETE("pickup/delegates/{id}")
    suspend fun deleteDelegate(@Path("id") id: String)

    @POST("pickup/requests")
    suspend fun createRequest(@Body body: CreatePickupRequest): PickupRequestDto

    @POST("pickup/requests/{id}/approve-early-leave")
    suspend fun approveEarlyLeave(@Path("id") id: String): PickupRequestDto

    @POST("pickup/requests/{id}/start-prep")
    suspend fun startPrep(@Path("id") id: String): PickupRequestDto

    @POST("pickup/requests/{id}/ready")
    suspend fun markReady(@Path("id") id: String): PickupRequestDto

    @POST("pickup/requests/{id}/handover")
    suspend fun handover(
        @Path("id") id: String,
        @Body body: HandoverRequest
    ): PickupRequestDto

    @POST("pickup/requests/{id}/cancel")
    suspend fun cancelRequest(
        @Path("id") id: String,
        @Body body: CancelPickupRequest
    ): PickupRequestDto

    @POST("pickup/requests/{id}/fail")
    suspend fun failRequest(
        @Path("id") id: String,
        @Body body: FailPickupRequest
    ): PickupRequestDto
}
