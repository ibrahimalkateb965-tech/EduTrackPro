package sa.gheras.edutrack.ui.notifications

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sa.gheras.edutrack.data.entity.NotificationEntity
import sa.gheras.edutrack.ui.common.EmptyView
import sa.gheras.edutrack.ui.common.LoadingView
import sa.gheras.edutrack.ui.common.Num
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    viewModel: NotificationsViewModel,
    modifier: Modifier = Modifier,
    onAction: (NotificationAction) -> Unit = {},
    onNavigateBack: (() -> Unit)? = null
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showBroadcastDialog by remember { mutableStateOf(false) }
    var announcementModalData by remember { mutableStateOf<NotificationAction.ShowAnnouncement?>(null) }
    var showSearchBar by remember { mutableStateOf(false) }

    // Listen to undo snackbar events
    LaunchedEffect(state.pendingUndoNotification) {
        state.pendingUndoNotification?.let { notif ->
            val result = snackbarHostState.showSnackbar(
                message = "تم حذف: \"${notif.title}\"",
                actionLabel = "تراجع",
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.undoDismiss()
            }
        }
    }

    // Listen to general user messages
    LaunchedEffect(state.userMessage) {
        state.userMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearUserMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("التنبيهات", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    if (onNavigateBack != null) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "رجوع"
                            )
                        }
                    }
                },
                actions = {
                    // Toggle Search Bar
                    IconButton(onClick = { showSearchBar = !showSearchBar }) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "بحث في التنبيهات",
                            tint = if (state.searchQuery.isNotBlank() || showSearchBar) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }

                    // Mark all read
                    if (state.unreadCount > 0) {
                        IconButton(
                            onClick = { viewModel.markAllAsRead() }
                        ) {
                            Icon(
                                imageVector = Icons.Default.DoneAll,
                                contentDescription = "تحديد الكل كمقروء",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    // Clear read notifications
                    IconButton(
                        onClick = { viewModel.clearReadNotifications() }
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = "مسح التنبيهات المقروءة",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Refresh
                    IconButton(
                        onClick = { viewModel.refresh() },
                        enabled = !state.isRefreshing
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "تحديث"
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            if (state.isTeacher) {
                ExtendedFloatingActionButton(
                    onClick = { showBroadcastDialog = true },
                    icon = { Icon(Icons.Default.Campaign, contentDescription = null) },
                    text = { Text("إرسال تعميم", fontWeight = FontWeight.Bold) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            }
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Refreshing indicator
            if (state.isRefreshing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // Expandable Search Bar
            AnimatedVisibility(
                visible = showSearchBar || state.searchQuery.isNotBlank(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = { viewModel.setSearchQuery(it) },
                    placeholder = { Text("ابحث في عنوان أو نص التنبيه...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.searchQuery.isNotBlank()) {
                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "مسح")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }

            // Filter Chips Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NotificationFilter.entries.forEach { filter ->
                    val isSelected = state.filter == filter
                    FilterChip(
                        selected = isSelected,
                        onClick = { viewModel.setFilter(filter) },
                        label = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(filter.title)
                                if (filter == NotificationFilter.UNREAD && state.unreadCount > 0) {
                                    Surface(
                                        shape = CircleShape,
                                        color = if (isSelected) {
                                            MaterialTheme.colorScheme.onPrimary
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        },
                                        modifier = Modifier.size(18.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = Num.formatInt(state.unreadCount),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (isSelected) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onPrimary
                                                },
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                }
            }

            // Main Content Area
            when {
                state.isLoading -> {
                    LoadingView()
                }
                state.groupedNotifications.isEmpty() -> {
                    val emptySubtitle = if (state.searchQuery.isNotBlank()) {
                        "لا توجد نتائج تطابق بحثك: \"${state.searchQuery}\""
                    } else if (state.filter == NotificationFilter.UNREAD) {
                        "رائع! لقد قرأت جميع التنبيهات"
                    } else {
                        "ستظهر هنا التنبيهات والتعاميم المدرسية فور وصولها"
                    }
                    EmptyView(
                        message = "لا توجد تنبيهات\n$emptySubtitle",
                        icon = Icons.Default.Campaign
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        state.groupedNotifications.forEach { (bucket, notifs) ->
                            item(key = "header_$bucket") {
                                DateGroupHeader(
                                    title = bucket,
                                    count = notifs.size,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                                )
                            }

                            items(
                                items = notifs,
                                key = { it.id }
                            ) { notification ->
                                val dismissState = rememberSwipeToDismissBoxState(
                                    confirmValueChange = { value ->
                                        if (value == SwipeToDismissBoxValue.EndToStart || value == SwipeToDismissBoxValue.StartToEnd) {
                                            viewModel.dismissNotification(notification)
                                            true
                                        } else {
                                            false
                                        }
                                    }
                                )

                                SwipeToDismissBox(
                                    state = dismissState,
                                    backgroundContent = {
                                        val color = MaterialTheme.colorScheme.errorContainer
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(color, RoundedCornerShape(12.dp))
                                                .padding(horizontal = 20.dp),
                                            contentAlignment = Alignment.CenterEnd
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Delete,
                                                    contentDescription = "حذف",
                                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                                )
                                                Text(
                                                    text = "حذف",
                                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    },
                                    content = {
                                        NotificationCard(
                                            notification = notification,
                                            onMarkAsRead = { viewModel.markAsRead(notification.id) },
                                            onClick = {
                                                val action = viewModel.resolveAction(notification)
                                                if (action is NotificationAction.ShowAnnouncement) {
                                                    announcementModalData = action
                                                } else {
                                                    onAction(action)
                                                }
                                            }
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Teacher Broadcast Dialog
    if (showBroadcastDialog) {
        CreateBroadcastDialog(
            rooms = state.rooms,
            onDismiss = { showBroadcastDialog = false },
            onSend = { title, body, priority, roomId, studentIds, incGuardians, incStudents ->
                showBroadcastDialog = false
                viewModel.broadcast(
                    title = title,
                    body = body,
                    priority = priority,
                    roomId = roomId,
                    studentIds = studentIds,
                    includeGuardians = incGuardians,
                    includeStudents = incStudents
                )
            }
        )
    }

    // Announcement Details Dialog
    announcementModalData?.let { announcement ->
        AlertDialog(
            onDismissRequest = { announcementModalData = null },
            icon = {
                Icon(
                    Icons.Default.Campaign,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    text = announcement.title,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!announcement.body.isNullOrBlank()) {
                        Text(
                            text = announcement.body,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    if (!announcement.senderName.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "مرسل الإعلان: ${announcement.senderName}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { announcementModalData = null }) {
                    Text("حسناً")
                }
            }
        )
    }
}

@Composable
private fun DateGroupHeader(
    title: String,
    count: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ) {
            Text(
                text = "${Num.formatInt(count)} تنبيه",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun NotificationCard(
    notification: NotificationEntity,
    onMarkAsRead: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isUnread = notification.readAt == null
    val isUrgent = notification.priority.equals("urgent", ignoreCase = true)
    val (kindTitle, kindIcon, kindColor) = resolveNotificationMeta(notification.kind)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        border = if (isUrgent) {
            BorderStroke(1.5.dp, MaterialTheme.colorScheme.error)
        } else if (isUnread) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
        } else null,
        colors = CardDefaults.cardColors(
            containerColor = if (isUrgent) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
            } else if (isUnread) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isUnread) 2.dp else 0.5.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Kind Avatar
            Surface(
                shape = CircleShape,
                color = if (isUrgent) MaterialTheme.colorScheme.error.copy(alpha = 0.15f) else kindColor.copy(alpha = 0.15f),
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isUrgent) Icons.Default.NotificationsActive else kindIcon,
                        contentDescription = null,
                        tint = if (isUrgent) MaterialTheme.colorScheme.error else kindColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                // Header row: badges + relative time
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = kindColor.copy(alpha = 0.12f)
                        ) {
                            Text(
                                text = kindTitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = kindColor,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }

                        if (isUrgent) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.error
                            ) {
                                Text(
                                    text = "عاجل",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onError,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val displayTime = notification.sentAt ?: notification.createdAt
                        Text(
                            text = formatArabicRelativeTime(displayTime),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        if (isUnread) {
                            Surface(
                                shape = CircleShape,
                                color = if (isUrgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(8.dp)
                            ) {}
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Title
                Text(
                    text = notification.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isUnread) FontWeight.Bold else FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // Body
                if (!notification.body.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = notification.body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = MaterialTheme.typography.bodyMedium.lineHeight,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Sender attribution if available
                if (!notification.senderName.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "بواسطة: ${notification.senderName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                // Action Footer
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!notification.actionUrl.isNullOrBlank() || !notification.targetType.isNullOrBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "عرض التفاصيل",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    if (isUnread) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.clickable(onClick = onMarkAsRead)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "تحديد كمقروء",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun resolveNotificationMeta(kind: String): Triple<String, ImageVector, Color> {
    return when {
        kind.contains("attendance", ignoreCase = true) -> Triple(
            "حضور وغياب",
            Icons.Default.EventAvailable,
            MaterialTheme.colorScheme.tertiary
        )
        kind.contains("assignment", ignoreCase = true) || kind.contains("homework", ignoreCase = true) -> Triple(
            "واجب دراسي",
            Icons.Default.Assignment,
            MaterialTheme.colorScheme.primary
        )
        kind.contains("evaluation", ignoreCase = true) || kind.contains("grade", ignoreCase = true) -> Triple(
            "تقييم يومي",
            Icons.Default.Star,
            Color(0xFFE65100)
        )
        else -> Triple(
            "تعميم وإعلان",
            Icons.Default.Campaign,
            MaterialTheme.colorScheme.secondary
        )
    }
}

private fun formatArabicRelativeTime(instant: Instant): String {
    val now = Instant.now()
    val diffSeconds = Duration.between(instant, now).seconds
    val zone = ZoneId.systemDefault()
    val localDateTime = LocalDateTime.ofInstant(instant, zone)
    val today = LocalDate.now(zone)
    val notifDate = localDateTime.toLocalDate()
    val timeStr = String.format(Locale.US, "%02d:%02d", localDateTime.hour, localDateTime.minute)

    return when {
        diffSeconds < 60 -> "الآن"
        diffSeconds < 3600 -> {
            val minutes = (diffSeconds / 60).toInt().coerceAtLeast(1)
            "منذ ${Num.formatInt(minutes)} دقيقة"
        }
        diffSeconds < 86400 && notifDate == today -> {
            val hours = (diffSeconds / 3600).toInt().coerceAtLeast(1)
            when (hours) {
                1 -> "منذ ساعة"
                2 -> "منذ ساعتين"
                in 3..10 -> "منذ ${Num.formatInt(hours)} ساعات"
                else -> "منذ ${Num.formatInt(hours)} ساعة"
            }
        }
        notifDate == today.minusDays(1) -> "أمس في $timeStr"
        else -> String.format(
            Locale.US,
            "%d-%02d-%02d %s",
            localDateTime.year,
            localDateTime.monthValue,
            localDateTime.dayOfMonth,
            timeStr
        )
    }
}
