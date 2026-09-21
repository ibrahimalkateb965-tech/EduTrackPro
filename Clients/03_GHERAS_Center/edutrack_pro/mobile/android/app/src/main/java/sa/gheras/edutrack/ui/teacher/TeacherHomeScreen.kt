package sa.gheras.edutrack.ui.teacher

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Grading
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import sa.gheras.edutrack.R
import sa.gheras.edutrack.ui.common.EmptyView
import sa.gheras.edutrack.ui.common.FailedWritesList
import sa.gheras.edutrack.ui.common.Num
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeacherHomeScreen(
    viewModel: TeacherHomeViewModel,
    onAttendanceClick: (roomId: String, date: String) -> Unit,
    onEvaluationClick: (roomId: String, date: String, subject: String) -> Unit,
    onLessonLogClick: (scheduleId: String, date: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState()
    val failedWrites by viewModel.failedWrites.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color.White,
                            shadowElevation = 1.dp,
                            modifier = Modifier
                                .size(width = 46.dp, height = 28.dp)
                                .padding(end = 8.dp)
                        ) {
                            Box(modifier = Modifier.fillMaxSize().padding(2.dp), contentAlignment = Alignment.Center) {
                                Image(
                                    painter = painterResource(id = R.drawable.gheras_logo),
                                    contentDescription = "شعار غراس",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                            }
                        }
                        Text("جدول الحصص", fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    if (pendingCount > 0) {
                        BadgedBox(
                            badge = {
                                Badge {
                                    Text(Num.formatInt(pendingCount))
                                }
                            },
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Sync,
                                contentDescription = "قيد المزامنة",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "تحديث")
                    }
                }
            )
        },
        modifier = modifier
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Day / Date Header
                item {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "${state.selectedDayName} — ${state.selectedDate}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Quick days selector
                        val daysOfWeek = listOf(
                            "الأحد" to LocalDate.now().minusDays(LocalDate.now().dayOfWeek.value.toLong() % 7),
                            "الاثنين" to LocalDate.now().minusDays(LocalDate.now().dayOfWeek.value.toLong() % 7).plusDays(1),
                            "الثلاثاء" to LocalDate.now().minusDays(LocalDate.now().dayOfWeek.value.toLong() % 7).plusDays(2),
                            "الأربعاء" to LocalDate.now().minusDays(LocalDate.now().dayOfWeek.value.toLong() % 7).plusDays(3),
                            "الخميس" to LocalDate.now().minusDays(LocalDate.now().dayOfWeek.value.toLong() % 7).plusDays(4)
                        )

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(daysOfWeek) { (name, date) ->
                                val isSelected = date == state.selectedDate
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { viewModel.selectDate(date) },
                                    label = { Text(name) }
                                )
                            }
                        }
                    }
                }

                // Failed writes list
                if (failedWrites.isNotEmpty()) {
                    item {
                        FailedWritesList(
                            failedWrites = failedWrites,
                            onRetry = viewModel::retryFailedWrite,
                            onDismiss = viewModel::dismissFailedWrite
                        )
                    }
                }

                // Weekend / Empty check
                if (state.isWeekend && state.slots.isEmpty()) {
                    item {
                        EmptyView(
                            message = "لا توجد حصص في عطلة نهاية الأسبوع (${state.selectedDayName})\nاختر يوماً دراسياً آخر من الأعلى",
                            icon = Icons.Default.DateRange
                        )
                    }
                } else if (state.slots.isEmpty()) {
                    item {
                        EmptyView(
                            message = "لم تُسند لك حصص في هذا اليوم — راجع الإدارة",
                            icon = Icons.Default.DateRange
                        )
                    }
                } else {
                    items(state.slots, key = { it.schedule.id }) { slotItem ->
                        ScheduleSlotCard(
                            slotItem = slotItem,
                            date = state.selectedDate.toString(),
                            onAttendanceClick = { onAttendanceClick(slotItem.schedule.roomId, state.selectedDate.toString()) },
                            onEvaluationClick = { onEvaluationClick(slotItem.schedule.roomId, state.selectedDate.toString(), slotItem.schedule.subject) },
                            onLessonLogClick = { onLessonLogClick(slotItem.schedule.id, state.selectedDate.toString()) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ScheduleSlotCard(
    slotItem: ScheduleSlotItem,
    date: String,
    onAttendanceClick: () -> Unit,
    onEvaluationClick: () -> Unit,
    onLessonLogClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sched = slotItem.schedule

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Text(
                        text = slotItem.roomName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = sched.subject,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Text(
                    text = "${sched.startTime} - ${sched.endTime}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onAttendanceClick,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = if (slotItem.isAttendanceDone) Icons.Default.CheckCircle else Icons.Default.FactCheck,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (slotItem.isAttendanceDone) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("حضور")
                }

                OutlinedButton(
                    onClick = onEvaluationClick,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = if (slotItem.isEvaluationDone) Icons.Default.CheckCircle else Icons.Default.Grading,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (slotItem.isEvaluationDone) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("تقييم")
                }

                OutlinedButton(
                    onClick = onLessonLogClick,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = if (slotItem.isLessonLogDone) Icons.Default.CheckCircle else Icons.Default.EditNote,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (slotItem.isLessonLogDone) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("السجل")
                }
            }
        }
    }
}
