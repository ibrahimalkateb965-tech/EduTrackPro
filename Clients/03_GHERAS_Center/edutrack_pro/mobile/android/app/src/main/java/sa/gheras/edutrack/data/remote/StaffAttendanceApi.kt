package sa.gheras.edutrack.data.remote

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import sa.gheras.edutrack.data.remote.dto.StaffAttendanceRecordDto
import sa.gheras.edutrack.data.remote.dto.StaffCheckInRequest
import sa.gheras.edutrack.data.remote.dto.StaffCheckOutRequest
import sa.gheras.edutrack.data.remote.dto.StaffHistoryResponseDto
import sa.gheras.edutrack.data.remote.dto.StaffStatusResponseDto
import sa.gheras.edutrack.data.remote.dto.SupervisorOverrideRequest

interface StaffAttendanceApi {

    @POST("staff-attendance/check-in")
    suspend fun checkIn(
        @Body body: StaffCheckInRequest
    ): StaffAttendanceRecordDto

    @POST("staff-attendance/check-out")
    suspend fun checkOut(
        @Body body: StaffCheckOutRequest
    ): StaffAttendanceRecordDto

    @GET("staff-attendance/my-status")
    suspend fun getMyStatus(): StaffStatusResponseDto

    @GET("staff-attendance/my-history")
    suspend fun getMyHistory(
        @Query("month") month: String? = null
    ): StaffHistoryResponseDto

    @GET("staff-attendance/supervisor/summary")
    suspend fun listForSupervisor(
        @Query("date") date: String? = null,
        @Query("month") month: String? = null,
        @Query("staff_id") staffId: String? = null
    ): List<StaffAttendanceRecordDto>

    @POST("staff-attendance/supervisor/override")
    suspend fun overrideAttendance(
        @Body body: SupervisorOverrideRequest
    ): StaffAttendanceRecordDto
}
