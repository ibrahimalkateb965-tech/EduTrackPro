package sa.gheras.edutrack.ui.guardian

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import sa.gheras.edutrack.data.repo.mappers.AssignmentMappers
import sa.gheras.edutrack.ui.common.EmptyView
import sa.gheras.edutrack.ui.common.LoadingView
import sa.gheras.edutrack.ui.common.Num

val RevisionOrange = Color(0xFFE65100)
val PresentGreen = Color(0xFF2E7D32)
val PendingNavy = Color(0xFF1565C0)
val SubmittedAmber = Color(0xFFF57F17)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChildHomeworkScreen(
    viewModel: ChildHomeworkViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToDetail: (assignmentId: String) -> Unit,
    onNavigateToResult: (assignmentId: String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("واجبات الطالب", fontWeight = FontWeight.Bold)
                        if (uiState.studentName.isNotBlank()) {
                            Text(
                                uiState.studentName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 5 Tabs Row
            ScrollableTabRow(
                selectedTabIndex = HomeworkTab.values().indexOf(uiState.selectedTab),
                edgePadding = 16.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                HomeworkTab.values().forEach { tab ->
                    val isSelected = uiState.selectedTab == tab
                    val count = uiState.counts[tab] ?: 0
                    Tab(
                        selected = isSelected,
                        onClick = { viewModel.selectTab(tab) },
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    tab.labelArabic,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                                if (count > 0) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Surface(
                                        shape = CircleShape,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                Num.format(count),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    )
                }
            }

            if (uiState.isLoading) {
                LoadingView(modifier = Modifier.fillMaxSize())
            } else if (uiState.items.isEmpty()) {
                EmptyView(
                    message = "لا توجد واجبات في هذا التبويب",
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(uiState.items, key = { it.assignment.id }) { item ->
                        ChildAssignmentCard(
                            item = item,
                            onClick = {
                                if (item.displayStatus == HomeworkTab.GRADED) {
                                    onNavigateToResult(item.assignment.id)
                                } else {
                                    onNavigateToDetail(item.assignment.id)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ChildAssignmentCard(
    item: ChildAssignmentItem,
    onClick: () -> Unit
) {
    val assignment = item.assignment
    val submission = item.latestSubmission

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: Subject & Status Badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = assignment.subject ?: "عام",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Status Badge
                StatusBadge(status = item.displayStatus, gradeLevel = submission?.gradeLevel)
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Title
            Text(
                text = assignment.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            // Instructions preview
            if (!assignment.instructions.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = assignment.instructions,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Footer: Due Date & Attached files indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Event,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (assignment.dueDate != null) {
                            "موعد التسليم: ${Num.formatDate(assignment.dueDate)}"
                        } else {
                            "موعد التسليم: غير محدد"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (item.files.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.AttachFile,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = Num.format(item.files.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StatusBadge(
    status: HomeworkTab,
    gradeLevel: String?
) {
    val (bgColor, textColor, text) = when (status) {
        HomeworkTab.PENDING -> Triple(Color(0xFFE3F2FD), PendingNavy, "مطلوب")
        HomeworkTab.SUBMITTED -> Triple(Color(0xFFFFF8E1), SubmittedAmber, "قيد المراجعة")
        HomeworkTab.GRADED -> {
            val badgeText = gradeLevel?.let { AssignmentMappers.gradeLevelToBadgeArabic(it) } ?: "تمت المراجعة"
            Triple(Color(0xFFE8F5E9), PresentGreen, badgeText)
        }
        HomeworkTab.REVISION_REQUESTED -> Triple(Color(0xFFFBE9E7), RevisionOrange, "إعادة الحل")
        HomeworkTab.ALL -> Triple(Color.LightGray, Color.DarkGray, "")
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = bgColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (status) {
                HomeworkTab.PENDING -> Icon(Icons.Default.HourglassEmpty, contentDescription = null, modifier = Modifier.size(14.dp), tint = textColor)
                HomeworkTab.SUBMITTED -> Icon(Icons.Default.Assignment, contentDescription = null, modifier = Modifier.size(14.dp), tint = textColor)
                HomeworkTab.GRADED -> Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(14.dp), tint = textColor)
                HomeworkTab.REVISION_REQUESTED -> Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp), tint = textColor)
                else -> {}
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = textColor,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
