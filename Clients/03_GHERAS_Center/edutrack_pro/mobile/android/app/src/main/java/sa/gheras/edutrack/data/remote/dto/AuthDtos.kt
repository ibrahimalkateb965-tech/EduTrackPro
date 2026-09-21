package sa.gheras.edutrack.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LoginBody(
    val username: String,
    val password: String
)

@Serializable
data class LoginResponse(
    val token: String,
    val user: ProfileUserDto
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
