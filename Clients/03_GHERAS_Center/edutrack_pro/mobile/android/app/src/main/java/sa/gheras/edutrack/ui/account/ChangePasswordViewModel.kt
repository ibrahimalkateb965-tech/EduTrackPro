package sa.gheras.edutrack.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import sa.gheras.edutrack.data.repo.SessionRepository

data class ChangePasswordUiState(
    val currentPassword: String = "",
    val newPassword: String = "",
    val confirmPassword: String = "",
    val isCurrentVisible: Boolean = false,
    val isNewVisible: Boolean = false,
    val isConfirmVisible: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null
)

class ChangePasswordViewModel(
    private val sessionRepository: SessionRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChangePasswordUiState())
    val uiState: StateFlow<ChangePasswordUiState> = _uiState.asStateFlow()

    fun onCurrentPasswordChange(value: String) = _uiState.update { it.copy(currentPassword = value, errorMessage = null) }
    fun onNewPasswordChange(value: String) = _uiState.update { it.copy(newPassword = value, errorMessage = null) }
    fun onConfirmPasswordChange(value: String) = _uiState.update { it.copy(confirmPassword = value, errorMessage = null) }

    fun toggleCurrentVisibility() = _uiState.update { it.copy(isCurrentVisible = !it.isCurrentVisible) }
    fun toggleNewVisibility() = _uiState.update { it.copy(isNewVisible = !it.isNewVisible) }
    fun toggleConfirmVisibility() = _uiState.update { it.copy(isConfirmVisible = !it.isConfirmVisible) }

    fun changePassword(onSuccess: () -> Unit) {
        val state = _uiState.value

        if (state.currentPassword.isBlank() || state.newPassword.isBlank() || state.confirmPassword.isBlank()) {
            _uiState.update { it.copy(errorMessage = "يرجى تعبئة جميع الحقول") }
            return
        }

        if (state.newPassword.length < 8) {
            _uiState.update { it.copy(errorMessage = "كلمة المرور الجديدة يجب ألا تقل عن 8 أحرف") }
            return
        }

        if (state.newPassword != state.confirmPassword) {
            _uiState.update { it.copy(errorMessage = "كلمة المرور وتأكيدها غير متطابقين") }
            return
        }

        if (state.newPassword == state.currentPassword) {
            _uiState.update { it.copy(errorMessage = "كلمة المرور الجديدة يجب أن تكون مختلفة عن الحالية") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = sessionRepository.changePassword(
                current = state.currentPassword,
                newPass = state.newPassword,
                confirm = state.confirmPassword
            )
            result.fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            successMessage = "تم تغيير كلمة المرور بنجاح"
                        )
                    }
                    onSuccess()
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.message ?: "تعذّر الاتصال بالخادم — حاول لاحقًا"
                        )
                    }
                }
            )
        }
    }
}
