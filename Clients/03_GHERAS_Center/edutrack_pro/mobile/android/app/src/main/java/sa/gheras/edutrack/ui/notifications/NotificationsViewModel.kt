package sa.gheras.edutrack.ui.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import sa.gheras.edutrack.data.entity.NotificationEntity
import sa.gheras.edutrack.data.local.session.SessionStore
import sa.gheras.edutrack.data.repo.NotificationsRepository
import sa.gheras.edutrack.sync.PullSync

enum class NotificationFilter(val title: String) {
    ALL("الكل"),
    UNREAD("غير المقروءة"),
    ATTENDANCE("الحضور"),
    ASSIGNMENTS("الواجبات"),
    GENERAL("إعلانات عامة")
}

data class NotificationsUiState(
    val notifications: List<NotificationEntity> = emptyList(),
    val unreadCount: Int = 0,
    val filter: NotificationFilter = NotificationFilter.ALL,
    val isRefreshing: Boolean = false,
    val isLoading: Boolean = true
)

class NotificationsViewModel(
    private val sessionStore: SessionStore,
    private val notificationsRepository: NotificationsRepository,
    private val pullSync: PullSync? = null
) : ViewModel() {

    private val currentUserId = sessionStore.user?.id ?: ""
    private val _filter = MutableStateFlow(NotificationFilter.ALL)
    private val _isRefreshing = MutableStateFlow(false)

    val uiState: StateFlow<NotificationsUiState> = combine(
        notificationsRepository.observeAll(currentUserId),
        _filter,
        _isRefreshing
    ) { allNotifs, filter, isRefreshing ->
        val sorted = allNotifs.sortedWith(
            compareBy<NotificationEntity> { it.readAt != null }
                .thenByDescending { it.createdAt }
        )

        val unreadCount = allNotifs.count { it.readAt == null }

        val filtered = when (filter) {
            NotificationFilter.ALL -> sorted
            NotificationFilter.UNREAD -> sorted.filter { it.readAt == null }
            NotificationFilter.ATTENDANCE -> sorted.filter {
                it.kind.contains("attendance", ignoreCase = true)
            }
            NotificationFilter.ASSIGNMENTS -> sorted.filter {
                it.kind.contains("assignment", ignoreCase = true) ||
                    it.kind.contains("homework", ignoreCase = true)
            }
            NotificationFilter.GENERAL -> sorted.filter {
                !it.kind.contains("attendance", ignoreCase = true) &&
                    !it.kind.contains("assignment", ignoreCase = true) &&
                    !it.kind.contains("homework", ignoreCase = true)
            }
        }

        NotificationsUiState(
            notifications = filtered,
            unreadCount = unreadCount,
            filter = filter,
            isRefreshing = isRefreshing,
            isLoading = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NotificationsUiState(isLoading = true))

    fun setFilter(filter: NotificationFilter) {
        _filter.value = filter
    }

    fun markAsRead(notificationId: String) {
        viewModelScope.launch {
            notificationsRepository.markRead(notificationId)
        }
    }

    fun markAllAsRead() {
        if (currentUserId.isBlank()) return
        viewModelScope.launch {
            notificationsRepository.markAllRead(currentUserId)
        }
    }

    fun refresh() {
        if (_isRefreshing.value || pullSync == null) return
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                pullSync.sync()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                _isRefreshing.value = false
            }
        }
    }
}
