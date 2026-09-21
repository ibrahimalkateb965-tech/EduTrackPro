package sa.gheras.edutrack.ui.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import sa.gheras.edutrack.data.entity.NotificationEntity
import sa.gheras.edutrack.data.local.session.SessionStore
import sa.gheras.edutrack.data.repo.NotificationsRepository

data class NotificationsUiState(
    val notifications: List<NotificationEntity> = emptyList(),
    val unreadCount: Int = 0,
    val isLoading: Boolean = true
)

class NotificationsViewModel(
    private val sessionStore: SessionStore,
    private val notificationsRepository: NotificationsRepository
) : ViewModel() {

    private val currentUserId = sessionStore.user?.id ?: ""

    val uiState: StateFlow<NotificationsUiState> = notificationsRepository.observeAll(currentUserId)
        .map { allNotifs ->
            val sorted = allNotifs.sortedWith(
                compareBy<NotificationEntity> { it.readAt != null }
                    .thenByDescending { it.createdAt }
            )

            NotificationsUiState(
                notifications = sorted,
                unreadCount = allNotifs.count { it.readAt == null },
                isLoading = false
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NotificationsUiState(isLoading = true))

    fun markAsRead(notificationId: String) {
        viewModelScope.launch {
            notificationsRepository.markRead(notificationId)
        }
    }
}
