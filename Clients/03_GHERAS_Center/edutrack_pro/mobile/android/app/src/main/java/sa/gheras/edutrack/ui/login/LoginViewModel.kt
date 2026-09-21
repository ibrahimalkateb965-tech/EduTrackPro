package sa.gheras.edutrack.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import sa.gheras.edutrack.data.repo.OutboxRepository
import sa.gheras.edutrack.data.repo.SessionRepository
import sa.gheras.edutrack.data.repo.SessionState

data class LoginUiState(
    val username: String = "",
    val password: String = "",
    val isPasswordVisible: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isUsernameLocked: Boolean = false,
    val isRenew: Boolean = false
)

class LoginViewModel(
    private val sessionRepository: SessionRepository,
    private val outboxRepository: OutboxRepository,
    initialMode: String = "Fresh"
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    val pendingCount: StateFlow<Int> = outboxRepository.pendingCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    init {
        val sessionState = sessionRepository.state.value
        when {
            initialMode == "Renew" && sessionState is SessionState.Active -> {
                _uiState.update {
                    it.copy(
                        username = sessionState.user.username,
                        isUsernameLocked = true,
                        isRenew = true
                    )
                }
            }
            sessionState is SessionState.Expired -> {
                _uiState.update {
                    it.copy(
                        username = sessionState.username,
                        isUsernameLocked = true,
                        isRenew = false
                    )
                }
            }
        }
    }

    fun onUsernameChange(username: String) {
        if (!_uiState.value.isUsernameLocked) {
            _uiState.update { it.copy(username = username, errorMessage = null) }
        }
    }

    fun onPasswordChange(password: String) {
        _uiState.update { it.copy(password = password, errorMessage = null) }
    }

    fun togglePasswordVisibility() {
        _uiState.update { it.copy(isPasswordVisible = !it.isPasswordVisible) }
    }

    fun login(onSuccess: () -> Unit = {}) {
        val state = _uiState.value
        if (state.username.isBlank() || state.password.isBlank()) {
            _uiState.update { it.copy(errorMessage = "يرجى إدخال اسم المستخدم وكلمة المرور") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = sessionRepository.login(state.username.trim(), state.password)
            result.fold(
                onSuccess = {
                    _uiState.update { it.copy(isLoading = false) }
                    onSuccess()
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.message ?: "فشل تسجيل الدخول — تأكد من صحة البيانات"
                        )
                    }
                }
            )
        }
    }

    fun switchAccount() {
        viewModelScope.launch {
            sessionRepository.logout()
            _uiState.update {
                LoginUiState(
                    username = "",
                    password = "",
                    isUsernameLocked = false,
                    isRenew = false
                )
            }
        }
    }
}
