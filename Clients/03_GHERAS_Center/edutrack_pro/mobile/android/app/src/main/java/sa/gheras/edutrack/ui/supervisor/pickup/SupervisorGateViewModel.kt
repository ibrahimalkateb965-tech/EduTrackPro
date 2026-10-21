package sa.gheras.edutrack.ui.supervisor.pickup

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

data class SupervisorGateUiState(
    val requests: List<PickupRequestDto> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

class SupervisorGateViewModel(
    private val pickupRepository: PickupRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SupervisorGateUiState(isLoading = true))
    val uiState: StateFlow<SupervisorGateUiState> = _uiState.asStateFlow()

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
                    errorMessage = err.message ?: "فشل تحميل قائمة البوابة"
                )
            }
        }
    }

    fun approveEarlyLeave(requestId: String, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val result = pickupRepository.approveEarlyLeave(requestId)
            result.onSuccess {
                refresh()
                onSuccess()
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = err.message ?: "فشل اعتماد الاستئذان المبكر"
                )
            }
        }
    }

    fun handover(
        requestId: String,
        verifiedReceiverIdNo: String,
        verifiedReceiverName: String,
        verifiedReceiverPhone: String,
        verifiedReceiverRelation: String,
        onSuccess: () -> Unit = {}
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val result = pickupRepository.handover(
                requestId = requestId,
                verifiedReceiverIdNo = verifiedReceiverIdNo,
                verifiedReceiverName = verifiedReceiverName,
                verifiedReceiverPhone = verifiedReceiverPhone,
                verifiedReceiverRelation = verifiedReceiverRelation
            )
            result.onSuccess {
                refresh()
                onSuccess()
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = err.message ?: "فشل تأكيد تسليم الطالب"
                )
            }
        }
    }

    fun reportFailure(requestId: String, reason: String, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val result = pickupRepository.failPickup(requestId, reason)
            result.onSuccess {
                refresh()
                onSuccess()
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = err.message ?: "فشل تسجيل تعذر التسليم"
                )
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }
}
