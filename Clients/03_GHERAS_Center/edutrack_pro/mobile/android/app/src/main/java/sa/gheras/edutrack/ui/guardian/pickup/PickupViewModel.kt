package sa.gheras.edutrack.ui.guardian.pickup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import sa.gheras.edutrack.data.entity.PickupDelegateEntity
import sa.gheras.edutrack.data.entity.StudentEntity
import sa.gheras.edutrack.data.remote.dto.PickupRequestDto
import sa.gheras.edutrack.data.repo.PickupRepository
import sa.gheras.edutrack.data.repo.StudentsRepository

data class PickupUiState(
    val student: StudentEntity? = null,
    val delegates: List<PickupDelegateEntity> = emptyList(),
    val selectedDelegateId: String? = null,
    val activeRequest: PickupRequestDto? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val requestSuccess: Boolean = false
)

class PickupViewModel(
    val studentId: String,
    private val studentsRepository: StudentsRepository,
    private val pickupRepository: PickupRepository
) : ViewModel() {

    private val _selectedDelegateId = MutableStateFlow<String?>(null)
    private val _activeRequest = MutableStateFlow<PickupRequestDto?>(null)
    private val _isLoading = MutableStateFlow(false)
    private val _errorMessage = MutableStateFlow<String?>(null)
    private val _requestSuccess = MutableStateFlow(false)

    val uiState: StateFlow<PickupUiState> = combine(
        combine(
            pickupRepository.observeDelegates(studentId),
            _selectedDelegateId,
            _activeRequest
        ) { delegates, selectedDelegate, activeReq -> Triple(delegates, selectedDelegate, activeReq) },
        combine(
            _isLoading,
            _errorMessage,
            _requestSuccess
        ) { loading, error, success -> Triple(loading, error, success) }
    ) { (delegates, selectedDelegate, activeReq), (loading, error, success) ->
        val student = studentsRepository.getById(studentId)
        PickupUiState(
            student = student,
            delegates = delegates,
            selectedDelegateId = selectedDelegate,
            activeRequest = activeReq,
            isLoading = loading,
            errorMessage = error,
            requestSuccess = success
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PickupUiState(isLoading = true))

    fun selectDelegate(delegateId: String?) {
        _selectedDelegateId.value = delegateId
    }

    fun requestPickup(isEarlyLeave: Boolean = false) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            val result = pickupRepository.requestPickup(
                studentId = studentId,
                delegateId = _selectedDelegateId.value,
                isEarlyLeave = isEarlyLeave
            )
            result.onSuccess { req ->
                _activeRequest.value = req
                _requestSuccess.value = true
                _isLoading.value = false
            }.onFailure { err ->
                _errorMessage.value = err.message ?: "فشل إرسال طلب الاستلام"
                _isLoading.value = false
            }
        }
    }

    fun addDelegate(name: String, nationalId: String, phone: String, relation: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            val result = pickupRepository.createDelegate(
                studentId = studentId,
                name = name,
                nationalId = nationalId,
                phone = phone,
                relation = relation
            )
            result.onFailure { err ->
                _errorMessage.value = err.message ?: "فشل إضافة المفوض"
            }
            _isLoading.value = false
        }
    }

    fun deleteDelegate(delegateId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            val result = pickupRepository.deleteDelegate(delegateId)
            result.onFailure { err ->
                _errorMessage.value = err.message ?: "فشل حذف المفوض"
            }
            _isLoading.value = false
        }
    }

    fun cancelActiveRequest(reason: String) {
        val reqId = _activeRequest.value?.id ?: return
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            val result = pickupRepository.cancelPickup(reqId, reason)
            result.onSuccess {
                _activeRequest.value = null
                _isLoading.value = false
            }.onFailure { err ->
                _errorMessage.value = err.message ?: "فشل إلغاء الطلب"
                _isLoading.value = false
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }
}
