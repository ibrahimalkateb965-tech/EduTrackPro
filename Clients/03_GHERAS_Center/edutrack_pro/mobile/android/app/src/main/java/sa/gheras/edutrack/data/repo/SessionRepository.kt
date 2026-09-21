package sa.gheras.edutrack.data.repo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import sa.gheras.edutrack.data.db.GherasDatabase
import sa.gheras.edutrack.data.local.session.Role
import sa.gheras.edutrack.data.local.session.SessionStore
import sa.gheras.edutrack.data.local.session.SessionUser
import sa.gheras.edutrack.data.remote.AuthApi
import sa.gheras.edutrack.data.remote.MeApi
import sa.gheras.edutrack.data.remote.dto.ChangePasswordBody
import sa.gheras.edutrack.data.remote.dto.LoginBody
import java.time.Instant

sealed interface SessionState {
    data object SignedOut : SessionState
    data class Active(val role: Role, val user: SessionUser) : SessionState
    data class Expired(val username: String) : SessionState
}

class SessionRepository(
    private val store: SessionStore,
    private val authApi: AuthApi,
    private val meApi: MeApi,
    private val db: GherasDatabase,
    private val onFullPullRequested: suspend () -> Unit = {}
) {
    private val _state = MutableStateFlow<SessionState>(initInitialState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private fun initInitialState(): SessionState {
        val token = store.token
        val user = store.user
        val tokenExp = store.tokenExp

        if (token != null && user != null) {
            if (tokenExp != null && Instant.now().isAfter(tokenExp)) {
                store.clearToken()
                return SessionState.Expired(user.username)
            }
            return SessionState.Active(user.role, user)
        }
        if (user != null) {
            return SessionState.Expired(user.username)
        }
        return SessionState.SignedOut
    }

    fun onUnauthorized() {
        _state.update { current ->
            if (current is SessionState.Active) {
                store.clearToken()
                SessionState.Expired(current.user.username)
            } else {
                current
            }
        }
    }

    suspend fun login(username: String, password: String, roleHint: Role? = null): Result<SessionUser> {
        return try {
            val response = authApi.login(LoginBody(username, password, roleHint?.name?.lowercase()))
            val role = when (response.user.role.lowercase()) {
                "teacher" -> Role.TEACHER
                "guardian" -> Role.GUARDIAN
                "student" -> Role.STUDENT
                else -> {
                    try { authApi.logout() } catch (_: Exception) {}
                    return Result.failure(IllegalStateException("هذا التطبيق مخصص للطلاب وأولياء الأمور والمعلمين — استخدم لوحة التحكم على الويب"))
                }
            }
            val user = SessionUser(
                id = response.user.id,
                username = response.user.username,
                role = role,
                name = response.user.name
            )

            // Identity gate: wipe local database if different user logs in
            if (store.lastUserId != null && store.lastUserId != user.id) {
                db.clearAllTables()
            }

            store.saveLogin(response.token, user)

            // Profile check
            try {
                val profile = meApi.profile()
                store.saveProfile(profile)
            } catch (e: Exception) {
                if (e is retrofit2.HttpException && e.code() == 403) {
                    store.clear()
                    db.clearAllTables()
                    _state.value = SessionState.SignedOut
                    return Result.failure(e)
                }
            }

            _state.value = SessionState.Active(role, user)
            onFullPullRequested()
            Result.success(user)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun logout() {
        try {
            authApi.logout()
        } catch (_: Exception) {}
        store.clear()
        db.clearAllTables()
        _state.value = SessionState.SignedOut
    }

    suspend fun changePassword(current: String, newPass: String, confirm: String? = null): Result<Unit> {
        return try {
            authApi.changePassword(ChangePasswordBody(current, newPass, confirm ?: newPass))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun discardExpiredSession() {
        store.clear()
        db.clearAllTables()
        _state.value = SessionState.SignedOut
    }
}
