package sa.gheras.edutrack.data.local.session

import sa.gheras.edutrack.data.remote.dto.ProfileDto
import java.time.Instant

enum class Role { TEACHER, GUARDIAN, STUDENT }

data class SessionUser(
    val id: String,
    val username: String,
    val role: Role,
    val name: String
)

interface SessionStore {
    val token: String?
    val tokenExp: Instant?
    val user: SessionUser?
    val profile: ProfileDto?
    val lastUserId: String?

    fun saveLogin(token: String, user: SessionUser)
    fun saveProfile(profile: ProfileDto)
    fun clearToken()
    fun clear()
}
