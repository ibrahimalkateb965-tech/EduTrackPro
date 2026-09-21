package sa.gheras.edutrack.ui.guardian

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Grading
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import sa.gheras.edutrack.ui.common.EmptyView
import sa.gheras.edutrack.ui.common.LoadingView
import sa.gheras.edutrack.ui.common.Num
import sa.gheras.edutrack.ui.theme.PresentGreen
import sa.gheras.edutrack.ui.theme.WarningAmber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuardianHomeScreen(
    viewModel: GuardianHomeViewModel,
    onAttendanceClick: (studentId: String) -> Unit,
    onEvaluationsClick: (studentId: String) -> Unit,
    onHomeworkClick: (studentId: String) -> Unit,
    onLessonsClick: (studentId: String) -> Unit,
    onSkillsClick: (studentId: String) -> Unit,
    onFeesClick: (studentId: String) -> Unit,
    showFees: Boolean = true,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("الرئيسية", fontWeight = FontWeight.Bold) },
                actions = {
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
            if (state.isLoading) {
                LoadingView()
            } else if (state.children.isEmpty()) {
                EmptyView(message = "لا يوجد أبناء مسجلين لحسابك — راجع إدارة المركز")
            } else {
                val child = state.selectedChild ?: return@PullToRefreshBox

                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Child Switcher (if multiple)
                    item {
                        ChildSwitcher(
                            children = state.children,
                            selectedChildId = child.id,
                            onChildSelected = viewModel::selectChild
                        )
                    }

                    // Welcome Card
                    item {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = if (showFees) "متابعة الطالب: ${child.name}" else "مرحباً يا بطل: ${child.name}",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = "مركز غراس للتعليم والتأهيل",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }

                    // 1. Today's Attendance
                    item {
                        val att = state.todayAttendance
                        val (attText, attColor) = when (att?.status) {
                            "حاضر" -> "حاضر اليوم" to PresentGreen
                            "غائب" -> "غائب اليوم" to MaterialTheme.colorScheme.error
                            "متأخر" -> "متأخر اليوم" to WarningAmber
                            "مستأذن" -> "مستأذن اليوم" to MaterialTheme.colorScheme.outline
                            else -> "لم يُسجل بعد" to MaterialTheme.colorScheme.outline
                        }

                        GuardianActionCard(
                            title = "حضور اليوم",
                            subtitle = attText,
                            icon = Icons.Default.FactCheck,
                            accentColor = attColor,
                            onClick = { onAttendanceClick(child.id) }
                        )
                    }

                    // 2. Latest Evaluations
                    item {
                        val evalsSummary = if (state.latestEvaluations.isNotEmpty()) {
                            state.latestEvaluations.joinToString(" • ") { "${it.subject}: ${Num.formatInt(it.value.toInt())}/10" }
                        } else {
                            "لا توجد تقييمات حديثة"
                        }

                        GuardianActionCard(
                            title = "التقييمات الأكاديمية",
                            subtitle = evalsSummary,
                            icon = Icons.Default.Grading,
                            onClick = { onEvaluationsClick(child.id) }
                        )
                    }

                    // 3. Homework Due
                    item {
                        GuardianActionCard(
                            title = "الواجبات والتكليفات",
                            subtitle = if (state.pendingHomeworkCount > 0) {
                                "لديك ${Num.formatInt(state.pendingHomeworkCount)} واجبات مستحقة"
                            } else {
                                "لا توجد واجبات مستحقة اليوم"
                            },
                            icon = Icons.Default.Assignment,
                            onClick = { onHomeworkClick(child.id) }
                        )
                    }

                    // 4. Lessons & Curriculum
                    item {
                        GuardianActionCard(
                            title = "الدروس وسجل الحصص",
                            subtitle = "متابعة المنهج والموضوعات المنجزة",
                            icon = Icons.Default.AutoStories,
                            onClick = { onLessonsClick(child.id) }
                        )
                    }

                    // 5. Skills
                    item {
                        GuardianActionCard(
                            title = "المهارات المكتسبة",
                            subtitle = "متابعة تقدم الطالب وإتقان المهارات",
                            icon = Icons.Default.Star,
                            onClick = { onSkillsClick(child.id) }
                        )
                    }

                    // 6. Fees (Hidden for student role)
                    if (showFees) {
                        item {
                            val feeSubtitle = if (state.nextUnpaidInstallment != null) {
                                val inst = state.nextUnpaidInstallment!!
                                "القسط القادم: ${Num.formatCurrency(inst.amount)} (استحقاق ${inst.dueDate ?: ""})"
                            } else {
                                "جميع الأقساط مسددة"
                            }

                            GuardianActionCard(
                                title = "الرسوم والأقساط",
                                subtitle = feeSubtitle,
                                icon = Icons.Default.CreditCard,
                                onClick = { onFeesClick(child.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GuardianActionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: androidx.compose.ui.graphics.Color? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = accentColor ?: MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = accentColor ?: MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}
