package sa.gheras.edutrack.ui.guardian

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import sa.gheras.edutrack.data.entity.InstallmentEntity
import sa.gheras.edutrack.data.entity.ReceiptEntity
import sa.gheras.edutrack.data.entity.StudentEntity
import sa.gheras.edutrack.data.repo.FeesRepository
import sa.gheras.edutrack.data.repo.StudentsRepository

data class FeesSummary(
    val totalAmount: Double = 0.0,
    val paidAmount: Double = 0.0,
    val remainingAmount: Double = 0.0
)

data class FeesUiState(
    val selectedChild: StudentEntity? = null,
    val children: List<StudentEntity> = emptyList(),
    val summary: FeesSummary = FeesSummary(),
    val installments: List<InstallmentEntity> = emptyList(),
    val receipts: List<ReceiptEntity> = emptyList(),
    val isLoading: Boolean = true
)

class FeesViewModel(
    initialStudentId: String? = null,
    private val studentsRepository: StudentsRepository,
    private val feesRepository: FeesRepository
) : ViewModel() {

    private val _selectedChildId = MutableStateFlow(initialStudentId)
    val selectedChildId: StateFlow<String?> = _selectedChildId.asStateFlow()

    val uiState: StateFlow<FeesUiState> = combine(
        studentsRepository.observeAll(),
        _selectedChildId
    ) { children, selectedId ->
        children to selectedId
    }.flatMapLatest { (children, selectedId) ->
        if (children.isEmpty()) {
            return@flatMapLatest flowOf(FeesUiState(isLoading = false))
        }

        val activeChild = children.find { it.id == selectedId } ?: children.first()
        val childId = activeChild.id

        combine(
            feesRepository.observeInstallments(childId),
            feesRepository.observeReceipts(childId)
        ) { installments, receipts ->
            val total = installments.sumOf { it.amount }
            val paid = receipts.sumOf { it.amount }.coerceAtLeast(installments.sumOf { it.paidAmount })
            val remaining = (total - paid).coerceAtLeast(0.0)

            FeesUiState(
                selectedChild = activeChild,
                children = children,
                summary = FeesSummary(totalAmount = total, paidAmount = paid, remainingAmount = remaining),
                installments = installments.sortedBy { it.dueDate },
                receipts = receipts.sortedByDescending { it.paidOn },
                isLoading = false
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FeesUiState(isLoading = true))

    fun selectChild(childId: String) {
        _selectedChildId.value = childId
    }
}
