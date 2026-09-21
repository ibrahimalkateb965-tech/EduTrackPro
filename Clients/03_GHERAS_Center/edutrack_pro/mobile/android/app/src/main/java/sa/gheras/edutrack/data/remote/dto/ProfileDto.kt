package sa.gheras.edutrack.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ProfileUserDto(
    val id: String,
    val username: String,
    val role: String,
    val name: String
)

@Serializable
data class ProfileScopeDto(
    @SerialName("room_ids") val roomIds: List<String> = emptyList(),
    @SerialName("student_ids") val studentIds: List<String> = emptyList()
)

@Serializable
data class ProfileCenterDto(
    val name: String? = null,
    val phone: String? = null
)

@Serializable
data class ProfileDto(
    val user: ProfileUserDto,
    val scope: ProfileScopeDto,
    val center: ProfileCenterDto? = null
)
