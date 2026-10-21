package sa.gheras.edutrack.ui.teacher.pickup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import sa.gheras.edutrack.data.remote.dto.PickupRequestDto
import sa.gheras.edutrack.data.remote.dto.PickupStatus
import sa.gheras.edutrack.data.repo.PickupRepository

data class TeacherPickupUiState(
    val requests: List<PickupRequestDto> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

class TeacherPickupViewModel(
    private val pickupRepository: PickupRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(TeacherPickupUiState(isLoading = true))
    val uiState: StateFlow<TeacherPickupUiState> = _uiState.asStateFlow()

    init {
        startPolling()
    }

    private fun startPolling() {
        viewModelScope.launch {
            while (true) {
                refresh()
                delay(5000)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val result = pickupRepository.getActiveRequests()
            result.onSuccess { list ->
                _uiState.value = _uiState.value.copy(
                    requests = list.filter { it.status in PickupStatus.ACTIVE_STATUSES },
                    isLoading = false,
                    errorMessage = null
                )
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = err.message ?: "فشل تحميل قائمة الاستلام"
                )
            }
        }
    }

    fun startPrep(requestId: String, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val result = pickupRepository.startPrep(requestId)
            result.onSuccess {
                refresh()
                onSuccess()
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = err.message ?: "فشل بدء التجهيز"
                )
            }
        }
    }

    fun markReady(requestId: String, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val result = pickupRepository.markReady(requestId)
            result.onSuccess {
                refresh()
                onSuccess()
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = err.message ?: "فشل تأكيد جاهزية الطالب"
                )
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }
}
