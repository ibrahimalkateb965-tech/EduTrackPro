package sa.gheras.edutrack.ui.supervisor.attendance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import sa.gheras.edutrack.data.remote.dto.AttendanceExcuseDto
import sa.gheras.edutrack.data.repo.AttendanceRepository

data class SupervisorExcusesUiState(
    val excuses: List<AttendanceExcuseDto> = emptyList(),
    val selectedFilter: String? = "pending",
    val selectedRoomId: String? = null,
    val isLoading: Boolean = false,
    val isProcessingAction: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null
)

class SupervisorExcusesViewModel(
    private val attendanceRepository: AttendanceRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SupervisorExcusesUiState())
    val uiState: StateFlow<SupervisorExcusesUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun setFilter(status: String?) {
        _uiState.value = _uiState.value.copy(selectedFilter = status)
        refresh()
    }

    fun setRoomId(roomId: String?) {
        _uiState.value = _uiState.value.copy(selectedRoomId = roomId)
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            try {
                val filter = _uiState.value.selectedFilter
                val items = attendanceRepository.listStaffExcuses(
                    status = filter,
                    roomId = _uiState.value.selectedRoomId
                )
                _uiState.value = _uiState.value.copy(
                    excuses = items,
                    isLoading = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = e.message ?: "فشل تحميل قائمة الأعذار للمشرفة"
                )
            }
        }
    }

    fun reviewExcuse(
        excuseId: String,
        action: String,
        notes: String? = null,
        onSuccess: () -> Unit = {}
    ) {
        val trimmedNotes = notes?.trim()
        if ((action == "reject" || action == "needs_info") && trimmedNotes.isNullOrEmpty()) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "ملاحظات المشرفة مطلوبة عند الرفض أو طلب الاستكمال"
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessingAction = true, errorMessage = null)
            try {
                attendanceRepository.reviewExcuse(
                    excuseId = excuseId,
                    action = action,
                    notes = trimmedNotes
                )
                val msg = when (action) {
                    "approve" -> "تم قبول العذر واعتماد غياب الطالب بعذر بنجاح"
                    "reject" -> "تم رفض العذر وإشعار ولي الأمر"
                    "needs_info" -> "تم طلب استكمال المستندات من ولي الأمر"
                    else -> "تم تحديث حالة العذر بنجاح"
                }
                _uiState.value = _uiState.value.copy(
                    isProcessingAction = false,
                    successMessage = msg
                )
                refresh()
                onSuccess()
            } catch (e: Exception) {
                val err = if (e.message?.contains("409") == true || e.message?.contains("Conflict") == true) {
                    "تم البت في هذا العذر مسبقاً أو حدث تعارض في البيانات"
                } else {
                    e.message ?: "فشل تحديث قرار العذر"
                }
                _uiState.value = _uiState.value.copy(
                    isProcessingAction = false,
                    errorMessage = err
                )
                refresh()
            }
        }
    }

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(
            errorMessage = null,
            successMessage = null
        )
    }
}
