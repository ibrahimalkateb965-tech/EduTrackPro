package sa.gheras.edutrack.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import sa.gheras.edutrack.data.remote.dto.ChangePasswordBody
import sa.gheras.edutrack.data.remote.dto.LoginBody
import sa.gheras.edutrack.data.remote.dto.LoginResponse
import sa.gheras.edutrack.data.remote.dto.RequestOtpBody
import sa.gheras.edutrack.data.remote.dto.RequestOtpResponse
import sa.gheras.edutrack.data.remote.dto.StatusResponse
import sa.gheras.edutrack.data.remote.dto.VerifyOtpBody

interface AuthApi {

    @POST("auth/login")
    suspend fun login(@Body body: LoginBody): LoginResponse

    @POST("auth/request-otp")
    suspend fun requestOtp(@Body body: RequestOtpBody): RequestOtpResponse

    @POST("auth/verify-otp")
    suspend fun verifyOtp(@Body body: VerifyOtpBody): LoginResponse

    @POST("auth/logout")
    suspend fun logout(): Response<Unit>

    @POST("auth/change-password")
    suspend fun changePassword(@Body body: ChangePasswordBody): StatusResponse
}
