package sa.gheras.edutrack.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import sa.gheras.edutrack.data.local.session.Role
import sa.gheras.edutrack.data.local.session.SessionStore
import sa.gheras.edutrack.data.local.session.SessionUser
import sa.gheras.edutrack.data.remote.dto.ProfileDto
import sa.gheras.edutrack.data.repo.OutboxRepository
import sa.gheras.edutrack.data.repo.SessionRepository
import sa.gheras.edutrack.sync.Outbox

data class AccountUiState(
    val user: SessionUser? = null,
    val profile: ProfileDto? = null,
    val isLoggingOut: Boolean = false,
    val errorMessage: String? = null
)

class AccountViewModel(
    private val sessionStore: SessionStore,
    private val sessionRepository: SessionRepository,
    private val outboxRepository: OutboxRepository,
    private val outbox: Outbox
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        AccountUiState(
            user = sessionStore.user,
            profile = sessionStore.profile
        )
    )
    val uiState: StateFlow<AccountUiState> = _uiState.asStateFlow()

    val pendingCount: StateFlow<Int> = outboxRepository.pendingCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun logout(onLoggedOut: () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoggingOut = true) }
            sessionRepository.logout()
            onLoggedOut()
        }
    }

    fun flushAndLogout(onLoggedOut: () -> Unit, onFlushFailed: () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoggingOut = true) }
            val flushResult = outbox.flush()
            if (flushResult.isSuccess) {
                sessionRepository.logout()
                onLoggedOut()
            } else {
                _uiState.update { it.copy(isLoggingOut = false, errorMessage = "تعذّر رفع التغييرات الآن") }
                onFlushFailed()
            }
        }
    }
}
