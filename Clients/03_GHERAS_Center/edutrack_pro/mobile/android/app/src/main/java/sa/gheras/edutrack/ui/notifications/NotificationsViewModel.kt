package sa.gheras.edutrack.ui.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import sa.gheras.edutrack.data.dao.RoomDao
import sa.gheras.edutrack.data.entity.NotificationEntity
import sa.gheras.edutrack.data.entity.RoomEntity
import sa.gheras.edutrack.data.local.session.Role
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
    val groupedNotifications: Map<String, List<NotificationEntity>> = emptyMap(),
    val unreadCount: Int = 0,
    val filter: NotificationFilter = NotificationFilter.ALL,
    val searchQuery: String = "",
    val isRefreshing: Boolean = false,
    val isLoading: Boolean = true,
    val rooms: List<RoomEntity> = emptyList(),
    val isTeacher: Boolean = false,
    val pendingUndoNotification: NotificationEntity? = null,
    val userMessage: String? = null
)

class NotificationsViewModel(
    private val sessionStore: SessionStore,
    private val notificationsRepository: NotificationsRepository,
    private val roomDao: RoomDao? = null,
    private val pullSync: PullSync? = null
) : ViewModel() {

    private val currentUserId = sessionStore.user?.id ?: ""
    val isTeacher = sessionStore.user?.role == Role.TEACHER

    private val _filter = MutableStateFlow(NotificationFilter.ALL)
    private val _searchQuery = MutableStateFlow("")
    private val _isRefreshing = MutableStateFlow(false)
    private val _rooms = MutableStateFlow<List<RoomEntity>>(emptyList())
    private val _pendingUndoNotification = MutableStateFlow<NotificationEntity?>(null)
    private val _userMessage = MutableStateFlow<String?>(null)

    private var pendingDeleteJob: Job? = null
    private var pendingDeleteId: String? = null

    init {
        if (isTeacher && roomDao != null) {
            viewModelScope.launch {
                roomDao.observeAll().collect { list ->
                    _rooms.value = list
                }
            }
        }
    }

    val uiState: StateFlow<NotificationsUiState> = combine(
        notificationsRepository.observeAll(currentUserId),
        _filter,
        _searchQuery,
        _isRefreshing,
        _rooms,
        _pendingUndoNotification,
        _userMessage
    ) { args: Array<Any?> ->
        @Suppress("UNCHECKED_CAST")
        val allNotifs = args[0] as List<NotificationEntity>
        val filter = args[1] as NotificationFilter
        val searchQuery = args[2] as String
        val isRefreshing = args[3] as Boolean
        @Suppress("UNCHECKED_CAST")
        val rooms = args[4] as List<RoomEntity>
        val pendingUndo = args[5] as? NotificationEntity
        val userMessage = args[6] as? String

        val sorted = allNotifs.sortedWith(
            compareBy<NotificationEntity> { it.readAt != null }
                .thenByDescending { it.sentAt ?: it.createdAt }
        )

        val unreadCount = allNotifs.count { it.readAt == null }

        val filteredByKind = when (filter) {
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

        val grouped = NotificationGrouping.groupNotifications(
            notifications = filteredByKind,
            query = searchQuery
        )

        val finalFlatList = grouped.values.flatten()

        NotificationsUiState(
            notifications = finalFlatList,
            groupedNotifications = grouped,
            unreadCount = unreadCount,
            filter = filter,
            searchQuery = searchQuery,
            isRefreshing = isRefreshing,
            isLoading = false,
            rooms = rooms,
            isTeacher = isTeacher,
            pendingUndoNotification = pendingUndo,
            userMessage = userMessage
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        NotificationsUiState(isLoading = true, isTeacher = isTeacher)
    )

    fun setFilter(filter: NotificationFilter) {
        _filter.value = filter
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
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

    fun clearReadNotifications() {
        if (currentUserId.isBlank()) return
        viewModelScope.launch {
            val count = notificationsRepository.clearRead(currentUserId)
            if (count > 0) {
                _userMessage.value = "تم مسح $count تنبيهات مقروءة"
            }
        }
    }

    fun dismissNotification(notification: NotificationEntity) {
        // If there was a previous pending deletion, commit it now
        commitPendingDeleteNow()

        pendingDeleteId = notification.id
        _pendingUndoNotification.value = notification

        viewModelScope.launch {
            notificationsRepository.softDeleteLocal(notification.id)
        }

        pendingDeleteJob = viewModelScope.launch {
            delay(4000)
            if (pendingDeleteId == notification.id) {
                notificationsRepository.commitDelete(notification.id)
                _pendingUndoNotification.value = null
                pendingDeleteId = null
            }
        }
    }

    fun undoDismiss() {
        val id = pendingDeleteId ?: return
        pendingDeleteJob?.cancel()
        pendingDeleteJob = null
        pendingDeleteId = null
        _pendingUndoNotification.value = null

        viewModelScope.launch {
            notificationsRepository.restoreLocal(id)
        }
    }

    private fun commitPendingDeleteNow() {
        val id = pendingDeleteId ?: return
        pendingDeleteJob?.cancel()
        pendingDeleteJob = null
        pendingDeleteId = null
        _pendingUndoNotification.value = null

        viewModelScope.launch {
            notificationsRepository.commitDelete(id)
        }
    }

    fun broadcast(
        title: String,
        body: String?,
        priority: String,
        roomId: String?,
        studentIds: List<String>,
        includeGuardians: Boolean,
        includeStudents: Boolean
    ) {
        viewModelScope.launch {
            try {
                notificationsRepository.broadcastNotification(
                    title = title,
                    body = body,
                    priority = priority,
                    roomId = roomId,
                    studentIds = studentIds,
                    includeGuardians = includeGuardians,
                    includeStudents = includeStudents
                )
                _userMessage.value = "تم إرسال التعميم بنجاح"
            } catch (e: Exception) {
                _userMessage.value = "فشل إرسال التعميم: ${e.message}"
            }
        }
    }

    fun clearUserMessage() {
        _userMessage.value = null
    }

    fun resolveAction(notification: NotificationEntity): NotificationAction {
        markAsRead(notification.id)
        return NotificationDeepLinkResolver.resolve(notification, sessionStore.user?.role)
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
