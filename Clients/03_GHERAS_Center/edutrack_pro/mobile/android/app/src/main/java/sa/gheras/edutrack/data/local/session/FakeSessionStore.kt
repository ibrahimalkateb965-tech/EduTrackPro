package sa.gheras.edutrack.data.local.session

import sa.gheras.edutrack.data.remote.dto.ProfileDto
import java.time.Instant

class FakeSessionStore : SessionStore {
    override var token: String? = null
    override var tokenExp: Instant? = null
    override var user: SessionUser? = null
    override var profile: ProfileDto? = null
    override var lastUserId: String? = null

    override fun saveLogin(token: String, user: SessionUser) {
        this.token = token
        this.user = user
        this.lastUserId = user.id
        val jwt = JwtPayload.decode(token)
        this.tokenExp = jwt?.expiresAt
    }

    override fun saveProfile(profile: ProfileDto) {
        this.profile = profile
    }

    override fun clearToken() {
        this.token = null
        this.tokenExp = null
    }

    override fun clear() {
        token = null
        tokenExp = null
        user = null
        profile = null
        lastUserId = null
    }
}
