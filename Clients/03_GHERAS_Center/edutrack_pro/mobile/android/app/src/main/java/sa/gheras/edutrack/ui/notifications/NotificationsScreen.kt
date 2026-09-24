package sa.gheras.edutrack.ui.notifications

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import sa.gheras.edutrack.data.entity.NotificationEntity
import sa.gheras.edutrack.ui.common.EmptyView
import sa.gheras.edutrack.ui.common.LoadingView
import sa.gheras.edutrack.ui.common.Num

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    viewModel: NotificationsViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("التنبيهات", fontWeight = FontWeight.Bold) },
                actions = {
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
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Refreshing bar indicator
            if (state.isRefreshing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // Filter Chips Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NotificationFilter.values().forEach { filter ->
                    val isSelected = state.filter == filter
                    val chipLabel = when (filter) {
                        NotificationFilter.ALL -> filter.title
                        NotificationFilter.UNREAD -> "${filter.title} (${Num.formatInt(state.unreadCount)})"
                        else -> filter.title
                    }
                    FilterChip(
                        selected = isSelected,
                        onClick = { viewModel.setFilter(filter) },
                        label = { Text(chipLabel) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }

            if (state.isLoading) {
                LoadingView(modifier = Modifier.weight(1f))
            } else if (state.notifications.isEmpty()) {
                val emptyMessage = when (state.filter) {
                    NotificationFilter.ALL -> "لا توجد تنبيهات حالياً"
                    NotificationFilter.UNREAD -> "رائع! لقد قرأت جميع التنبيهات"
                    NotificationFilter.ATTENDANCE -> "لا توجد تنبيهات خاصة بالحضور والغياب"
                    NotificationFilter.ASSIGNMENTS -> "لا توجد تنبيهات خاصة بالواجبات"
                    NotificationFilter.GENERAL -> "لا توجد إعلانات عامة حالياً"
                }
                EmptyView(
                    message = emptyMessage,
                    icon = Icons.Default.Notifications,
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    // Unread summary banner when in ALL filter
                    if (state.filter == NotificationFilter.ALL && state.unreadCount > 0) {
                        item(key = "unread_banner") {
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.NotificationsActive,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Text(
                                            text = "لديك ${Num.formatInt(state.unreadCount)} تنبيهات غير مقروءة",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                    TextButton(
                                        onClick = { viewModel.markAllAsRead() },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text("قراءة الكل", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }

                    items(state.notifications, key = { it.id }) { notif ->
                        NotificationCard(
                            notification = notif,
                            onClick = {
                                if (notif.readAt == null) {
                                    viewModel.markAsRead(notif.id)
                                }
                            },
                            onMarkAsRead = {
                                viewModel.markAsRead(notif.id)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun NotificationCard(
    notification: NotificationEntity,
    onClick: () -> Unit,
    onMarkAsRead: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isUnread = notification.readAt == null

    val (kindTitle, kindIcon, kindColor) = resolveNotificationMeta(notification.kind)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        border = if (isUnread) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
        } else null,
        colors = CardDefaults.cardColors(
            containerColor = if (isUnread) {
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
            // Kind Avatar / Icon
            Surface(
                shape = CircleShape,
                color = kindColor.copy(alpha = 0.15f),
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = kindIcon,
                        contentDescription = null,
                        tint = kindColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                // Header row: Kind badge + Relative time + Unread dot
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
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

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = formatArabicRelativeTime(notification.createdAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        if (isUnread) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary,
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
                        lineHeight = MaterialTheme.typography.bodyMedium.lineHeight
                    )
                }

                // If unread, quick "تحديد كمقروء" action
                if (isUnread) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
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
            "تنبيه عام",
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
