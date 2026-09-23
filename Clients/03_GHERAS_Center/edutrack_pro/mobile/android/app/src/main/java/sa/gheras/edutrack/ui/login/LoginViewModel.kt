package sa.gheras.edutrack.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import sa.gheras.edutrack.data.local.session.Role
import sa.gheras.edutrack.data.repo.OutboxRepository
import sa.gheras.edutrack.data.repo.SessionRepository
import sa.gheras.edutrack.data.repo.SessionState
import sa.gheras.edutrack.ui.common.Num

enum class AuthMethod {
    WHATSAPP_OTP,
    PASSWORD
}

data class LoginUiState(
    val username: String = "",
    val password: String = "",
    val selectedRole: Role = Role.GUARDIAN,
    val isPasswordVisible: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isUsernameLocked: Boolean = false,
    val isRenew: Boolean = false,
    val authMethod: AuthMethod = AuthMethod.WHATSAPP_OTP,
    val isOtpSent: Boolean = false,
    val otpSessionId: String? = null,
    val phoneMasked: String? = null,
    val otpCode: String = "",
    val cooldownSeconds: Int = 0
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

    private var timerJob: Job? = null

    init {
        val sessionState = sessionRepository.state.value
        when {
            initialMode == "Renew" && sessionState is SessionState.Active -> {
                _uiState.update {
                    it.copy(
                        username = sessionState.user.username,
                        selectedRole = sessionState.role,
                        isUsernameLocked = true,
                        isRenew = true,
                        authMethod = AuthMethod.PASSWORD
                    )
                }
            }
            sessionState is SessionState.Expired -> {
                _uiState.update {
                    it.copy(
                        username = sessionState.username,
                        isUsernameLocked = true,
                        isRenew = false,
                        authMethod = AuthMethod.PASSWORD
                    )
                }
            }
        }
    }

    fun onRoleChange(role: Role) {
        if (!_uiState.value.isUsernameLocked) {
            val nextAuthMethod = if (role == Role.TEACHER) AuthMethod.PASSWORD else AuthMethod.WHATSAPP_OTP
            _uiState.update {
                it.copy(
                    selectedRole = role,
                    errorMessage = null,
                    isOtpSent = false,
                    authMethod = nextAuthMethod
                )
            }
        }
    }

    fun onUsernameChange(username: String) {
        if (!_uiState.value.isUsernameLocked) {
            val sanitized = Num.enforceWesternNumerals(username)
            _uiState.update { it.copy(username = sanitized, errorMessage = null) }
        }
    }

    fun onPasswordChange(password: String) {
        _uiState.update { it.copy(password = password, errorMessage = null) }
    }

    fun onOtpCodeChange(code: String) {
        val sanitized = Num.enforceWesternNumerals(code).filter { it.isDigit() }.take(6)
        _uiState.update { it.copy(otpCode = sanitized, errorMessage = null) }
    }

    fun togglePasswordVisibility() {
        _uiState.update { it.copy(isPasswordVisible = !it.isPasswordVisible) }
    }

    fun toggleAuthMethod() {
        _uiState.update {
            val next = if (it.authMethod == AuthMethod.WHATSAPP_OTP) AuthMethod.PASSWORD else AuthMethod.WHATSAPP_OTP
            it.copy(authMethod = next, errorMessage = null)
        }
    }

    fun resetOtpFlow() {
        _uiState.update {
            it.copy(isOtpSent = false, otpSessionId = null, otpCode = "", errorMessage = null)
        }
    }

    fun sendOtp() {
        val state = _uiState.value
        val identity = state.username.trim()
        if (identity.isBlank()) {
            val msg = if (state.selectedRole == Role.TEACHER) {
                "يرجى إدخال رقم الجوال أو رقم الهوية"
            } else {
                "يرجى إدخال رقم الهوية الوطنية أو الإقامة"
            }
            _uiState.update { it.copy(errorMessage = msg) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = sessionRepository.requestOtp(identity, state.selectedRole)
            result.fold(
                onSuccess = { res ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isOtpSent = true,
                            otpSessionId = res.sessionId,
                            phoneMasked = res.phoneMasked,
                            cooldownSeconds = res.resendCooldown,
                            errorMessage = null
                        )
                    }
                    startCooldownTimer(res.resendCooldown)
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.message ?: "تعذر إرسال رمز التحقق — تحقق من رقم الهوية"
                        )
                    }
                }
            )
        }
    }

    private fun startCooldownTimer(seconds: Int) {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            var current = seconds
            while (current > 0) {
                delay(1000)
                current--
                _uiState.update { it.copy(cooldownSeconds = current) }
            }
        }
    }

    fun verifyOtp(onSuccess: () -> Unit = {}) {
        val state = _uiState.value
        val code = state.otpCode.trim()
        val sessionId = state.otpSessionId
        if (sessionId.isNullOrBlank()) {
            _uiState.update { it.copy(errorMessage = "جلسة التحقق غير صالحة، يرجى إعادة طلب الرمز") }
            return
        }
        if (code.length < 4) {
            _uiState.update { it.copy(errorMessage = "يرجى إدخال رمز التحقق المكون من 4 أرقام") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = sessionRepository.verifyOtp(sessionId, code, state.selectedRole)
            result.fold(
                onSuccess = {
                    _uiState.update { it.copy(isLoading = false) }
                    onSuccess()
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.message ?: "رمز التحقق غير صحيح، يرجى المحاولة ثانية"
                        )
                    }
                }
            )
        }
    }

    fun login(onSuccess: () -> Unit = {}) {
        val state = _uiState.value
        if (state.authMethod == AuthMethod.WHATSAPP_OTP) {
            if (state.isOtpSent) {
                verifyOtp(onSuccess)
            } else {
                sendOtp()
            }
            return
        }

        val identity = state.username.trim()
        if (identity.isBlank()) {
            val msg = if (state.selectedRole == Role.TEACHER) {
                "يرجى إدخال رقم الجوال أو اسم المستخدم أو الهوية"
            } else {
                "يرجى إدخال رقم الهوية الوطنية أو الإقامة"
            }
            _uiState.update { it.copy(errorMessage = msg) }
            return
        }
        if (state.password.isBlank()) {
            _uiState.update { it.copy(errorMessage = "يرجى إدخال كلمة المرور") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = sessionRepository.login(identity, state.password, state.selectedRole)
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
        timerJob?.cancel()
        viewModelScope.launch {
            sessionRepository.logout()
            _uiState.update {
                LoginUiState(
                    username = "",
                    password = "",
                    isUsernameLocked = false,
                    isRenew = false,
                    authMethod = AuthMethod.WHATSAPP_OTP
                )
            }
        }
    }
}
