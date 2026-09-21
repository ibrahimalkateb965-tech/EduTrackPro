package sa.gheras.edutrack.ui.common

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Empty(val message: String = "لا توجد بيانات متاحة") : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}
