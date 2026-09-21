package sa.gheras.edutrack.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LoginBody(
    val username: String,
    val password: String,
    val role: String? = null
)

@Serializable
data class LoginResponse(
    val token: String,
    val user: ProfileUserDto
)

@Serializable
data class RequestOtpBody(
    @SerialName("national_id") val nationalId: String,
    val role: String
)

@Serializable
data class RequestOtpResponse(
    @SerialName("session_id") val sessionId: String,
    @SerialName("phone_masked") val phoneMasked: String,
    @SerialName("expires_in") val expiresIn: Int = 300,
    @SerialName("resend_cooldown") val resendCooldown: Int = 60
)

@Serializable
data class VerifyOtpBody(
    @SerialName("session_id") val sessionId: String,
    @SerialName("otp_code") val otpCode: String,
    val role: String? = null
)

@Serializable
data class ChangePasswordBody(
    @SerialName("current_password") val currentPassword: String,
    @SerialName("new_password") val newPassword: String,
    @SerialName("confirm_password") val confirmPassword: String? = null
)

@Serializable
data class StatusResponse(
    val status: String,
    val message: String? = null
)
