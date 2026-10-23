package sa.gheras.edutrack.ui.guardian.report

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import sa.gheras.edutrack.data.remote.dto.AttendanceStatsDto
import sa.gheras.edutrack.data.remote.dto.SubjectRatingDto
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChildMonthlyReportScreen(
    viewModel: ChildMonthlyReportViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
        }
    }

    LaunchedEffect(state.pdfUrl) {
        state.pdfUrl?.let { url ->
            val fullUrl = if (url.startsWith("http")) url else "http://localhost:8000$url"
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(fullUrl))
                context.startActivity(intent)
            } catch (_: Exception) {
                snackbarHostState.showSnackbar("رابط التقرير: $fullUrl")
            }
            viewModel.clearPdfUrl()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("التقرير الشهري للطالب", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }, enabled = !state.isLoading) {
                        Icon(Icons.Default.Refresh, contentDescription = "تحديث")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (state.report != null) {
                Surface(
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Button(
                            onClick = { viewModel.downloadPdf() },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.isDownloadingPdf,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            if (state.isDownloadingPdf) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                            } else {
                                Icon(Icons.Default.Download, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("تحميل التقرير الرسمي (PDF)")
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Month Selector
            GuardianMonthFilter(
                currentMonthStr = state.selectedMonth,
                onMonthChanged = { viewModel.selectMonth(it) }
            )

            if (state.isLoading && state.report == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (state.report == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            Icons.Default.CalendarMonth,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "لم يتم نشر التقرير الشهري لهذا الشهر بعد",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "سيظهر التقرير هنا فور اعتماده ونشره من قِبل إدارة المركز",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                val rep = state.report!!
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 1. Header Information Card
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        state.student?.name ?: rep.studentName ?: "الطالب",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "المعلمة المشرفة: ${rep.teacherName ?: "معلمة الفصل"}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                    )
                                }
                                Icon(
                                    Icons.Default.EmojiEvents,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }
                    }

                    // 2. Attendance Stats
                    item {
                        GuardianAttendanceCard(rep.attendanceStats)
                    }

                    // 3. Subject Ratings Title
                    item {
                        Text(
                            "التقييم الأكاديمي والمهاري للمواد",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    items(rep.subjectRatings) { subjectRating ->
                        SubjectRatingDisplayCard(subjectRating)
                    }

                    // 4. Qualitative Assessments
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (!rep.strengths.isNullOrBlank()) {
                                EvaluationFeedbackCard(
                                    title = "نقاط القوة والتميز",
                                    content = rep.strengths,
                                    icon = Icons.Default.ThumbUp,
                                    containerColor = Color(0xFFE8F5E9),
                                    contentColor = Color(0xFF2E7D32)
                                )
                            }

                            if (!rep.improvements.isNullOrBlank()) {
                                EvaluationFeedbackCard(
                                    title = "مجالات التطوير المستمر",
                                    content = rep.improvements,
                                    icon = Icons.Default.Lightbulb,
                                    containerColor = Color(0xFFFFF3E0),
                                    contentColor = Color(0xFFE65100)
                                )
                            }

                            if (!rep.recommendations.isNullOrBlank()) {
                                EvaluationFeedbackCard(
                                    title = "توصيات المعلمة للأسرة",
                                    content = rep.recommendations,
                                    icon = Icons.Default.MenuBook,
                                    containerColor = Color(0xFFE3F2FD),
                                    contentColor = Color(0xFF1565C0)
                                )
                            }
                        }
                    }

                    item {
                        Spacer(Modifier.height(48.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun GuardianMonthFilter(
    currentMonthStr: String,
    onMonthChanged: (String) -> Unit
) {
    val ym = try {
        YearMonth.parse(currentMonthStr, DateTimeFormatter.ofPattern("yyyy-MM"))
    } catch (_: Exception) {
        YearMonth.now()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = {
            val prev = ym.minusMonths(1)
            onMonthChanged(prev.format(DateTimeFormatter.ofPattern("yyyy-MM")))
        }) {
            Icon(Icons.Default.ChevronRight, contentDescription = "الشهر السابق")
        }

        Text(
            text = ym.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        IconButton(onClick = {
            val next = ym.plusMonths(1)
            onMonthChanged(next.format(DateTimeFormatter.ofPattern("yyyy-MM")))
        }) {
            Icon(Icons.Default.ChevronLeft, contentDescription = "الشهر التالي")
        }
    }
}

@Composable
private fun GuardianAttendanceCard(stats: AttendanceStatsDto?) {
    val s = stats ?: AttendanceStatsDto()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("مؤشرات الحضور والانضباط", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${s.attendanceRate}%", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color(0xFF1565C0))
                    Text("نسبة الحضور", style = MaterialTheme.typography.labelSmall)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${s.presentDays}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                    Text("أيام الحضور", style = MaterialTheme.typography.labelSmall)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${s.excusedAbsentDays}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color(0xFFF57C00))
                    Text("بعذر", style = MaterialTheme.typography.labelSmall)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${s.absentDays}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color(0xFFC62828))
                    Text("غياب", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun SubjectRatingDisplayCard(rating: SubjectRatingDto) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(rating.subject, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (!rating.notes.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(rating.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Surface(
                shape = MaterialTheme.shapes.small,
                color = when (rating.rating) {
                    "ممتاز" -> Color(0xFFE8F5E9)
                    "جيد جداً" -> Color(0xFFE3F2FD)
                    "جيد" -> Color(0xFFFFF3E0)
                    else -> Color(0xFFFFEBEE)
                }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = null,
                        tint = when (rating.rating) {
                            "ممتاز" -> Color(0xFF2E7D32)
                            "جيد جداً" -> Color(0xFF1565C0)
                            "جيد" -> Color(0xFFF57C00)
                            else -> Color(0xFFC62828)
                        },
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        rating.rating,
                        fontWeight = FontWeight.Bold,
                        color = when (rating.rating) {
                            "ممتاز" -> Color(0xFF2E7D32)
                            "جيد جداً" -> Color(0xFF1565C0)
                            "جيد" -> Color(0xFFF57C00)
                            else -> Color(0xFFC62828)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun EvaluationFeedbackCard(
    title: String,
    content: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    containerColor: Color,
    contentColor: Color
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, fontWeight = FontWeight.Bold, color = contentColor)
            }
            Spacer(Modifier.height(8.dp))
            Text(content, style = MaterialTheme.typography.bodyMedium, color = contentColor)
        }
    }
}
