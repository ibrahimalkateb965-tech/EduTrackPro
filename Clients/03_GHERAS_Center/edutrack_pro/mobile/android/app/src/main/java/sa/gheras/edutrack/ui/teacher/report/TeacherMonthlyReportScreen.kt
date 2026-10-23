package sa.gheras.edutrack.ui.teacher.report

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeacherMonthlyReportScreen(
    viewModel: TeacherMonthlyReportViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.successMessage) {
        state.successMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessages()
        }
    }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessages()
        }
    }

    val isEditable = state.currentReport == null ||
            state.currentReport?.status == "draft" ||
            state.currentReport?.status == "revision_requested"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("التقارير الشهرية للطلاب", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (isEditable) {
                Surface(
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.saveDraft() },
                            modifier = Modifier.weight(1f),
                            enabled = !state.isSaving
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("حفظ مسودة")
                        }

                        Button(
                            onClick = { viewModel.submitReport() },
                            modifier = Modifier.weight(1f),
                            enabled = !state.isSaving
                        ) {
                            if (state.isSaving) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary)
                            } else {
                                Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("إرسال للاعتماد")
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        if (state.isLoading && state.students.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. Month Navigator
                item {
                    MonthNavigatorCard(
                        currentMonthStr = state.selectedMonth,
                        onMonthChanged = { viewModel.selectMonth(it) }
                    )
                }

                // 2. Student Selector Chips
                item {
                    Text("اختر الطالب:", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        state.students.forEach { student ->
                            FilterChip(
                                selected = student.id == state.selectedStudentId,
                                onClick = { viewModel.selectStudent(student.id) },
                                label = { Text(student.name) }
                            )
                        }
                    }
                }

                // 3. Status Banner
                item {
                    ReportStatusCard(
                        status = state.currentReport?.status ?: "draft",
                        revisionNotes = state.revisionNotes
                    )
                }

                // 4. Attendance Statistics
                item {
                    AttendanceStatsCard(stats = state.attendanceStats)
                }

                // 5. Subject Ratings
                item {
                    Text("تقييم المواد الدراسية", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }

                val subjects = listOf("القرآن", "لغتي", "الإنجليزي", "الرياضيات")
                items(subjects.size) { index ->
                    val subj = subjects[index]
                    val ratingPair = state.subjectRatings[subj] ?: Pair("ممتاز", "")
                    SubjectRatingEditorCard(
                        subject = subj,
                        currentRating = ratingPair.first,
                        notes = ratingPair.second,
                        isEditable = isEditable,
                        onRatingChanged = { newRating ->
                            viewModel.updateRating(subj, newRating, ratingPair.second)
                        },
                        onNotesChanged = { newNotes ->
                            viewModel.updateRating(subj, ratingPair.first, newNotes)
                        }
                    )
                }

                // 6. Qualitative Feedback
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("التقييم السلوكي والتوصيات", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                            OutlinedTextField(
                                value = state.strengths,
                                onValueChange = { viewModel.updateStrengths(it) },
                                label = { Text("نقاط القوة والإشادة") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2,
                                enabled = isEditable
                            )

                            OutlinedTextField(
                                value = state.improvements,
                                onValueChange = { viewModel.updateImprovements(it) },
                                label = { Text("مجالات التحسين") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2,
                                enabled = isEditable
                            )

                            OutlinedTextField(
                                value = state.recommendations,
                                onValueChange = { viewModel.updateRecommendations(it) },
                                label = { Text("توصيات وتوجيهات لولي الأمر") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2,
                                enabled = isEditable
                            )
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}

@Composable
private fun MonthNavigatorCard(
    currentMonthStr: String,
    onMonthChanged: (String) -> Unit
) {
    val ym = try {
        YearMonth.parse(currentMonthStr, DateTimeFormatter.ofPattern("yyyy-MM"))
    } catch (_: Exception) {
        YearMonth.now()
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                val prev = ym.minusMonths(1)
                onMonthChanged(prev.format(DateTimeFormatter.ofPattern("yyyy-MM")))
            }) {
                Icon(Icons.Default.ChevronRight, contentDescription = "الشهر السابق")
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = ym.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = ym.format(DateTimeFormatter.ofPattern("yyyy-MM")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = {
                val next = ym.plusMonths(1)
                onMonthChanged(next.format(DateTimeFormatter.ofPattern("yyyy-MM")))
            }) {
                Icon(Icons.Default.ChevronLeft, contentDescription = "الشهر التالي")
            }
        }
    }
}

@Composable
private fun ReportStatusCard(status: String, revisionNotes: String?) {
    val (label, containerColor, contentColor, icon) = when (status) {
        "pending_approval" -> Quadruple("قيد مراجعة المشرفة", Color(0xFFFFF3E0), Color(0xFFE65100), Icons.Default.Lock)
        "revision_requested" -> Quadruple("مطلوب تعديل من المشرفة", Color(0xFFFFEBEE), Color(0xFFC62828), Icons.Default.Warning)
        "approved" -> Quadruple("معتمد من المشرفة", Color(0xFFE8F5E9), Color(0xFF2E7D32), Icons.Default.DoneAll)
        "published" -> Quadruple("منشور ومتاح لولي الأمر", Color(0xFFE0F2F1), Color(0xFF00695C), Icons.Default.DoneAll)
        else -> Quadruple("مسودة داخلية", Color(0xFFEDE7F6), Color(0xFF512DA8), Icons.Default.Info)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(label, fontWeight = FontWeight.Bold, color = contentColor)
            }
            if (status == "revision_requested" && !revisionNotes.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "ملاحظات المشرفة: $revisionNotes",
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor
                )
            }
        }
    }
}

@Composable
private fun AttendanceStatsCard(stats: sa.gheras.edutrack.data.remote.dto.AttendanceStatsDto?) {
    val s = stats ?: sa.gheras.edutrack.data.remote.dto.AttendanceStatsDto()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("إحصائيات الحضور والغياب للشهر", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatColumn(title = "الأيام الكلية", value = "${s.totalDays}")
                StatColumn(title = "أيام الحضور", value = "${s.presentDays}", color = Color(0xFF2E7D32))
                StatColumn(title = "غياب بعذر", value = "${s.excusedAbsentDays}", color = Color(0xFFF57C00))
                StatColumn(title = "غياب", value = "${s.absentDays}", color = Color(0xFFC62828))
                StatColumn(title = "نسبة الحضور", value = "${s.attendanceRate}%", color = Color(0xFF1565C0))
            }
        }
    }
}

@Composable
private fun StatColumn(title: String, value: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
        Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SubjectRatingEditorCard(
    subject: String,
    currentRating: String,
    notes: String,
    isEditable: Boolean,
    onRatingChanged: (String) -> Unit,
    onNotesChanged: (String) -> Unit
) {
    val ratingOptions = listOf("ممتاز", "جيد جداً", "جيد", "يحتاج متابعة")

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(subject, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ratingOptions.forEach { rating ->
                    FilterChip(
                        selected = currentRating == rating,
                        onClick = { if (isEditable) onRatingChanged(rating) },
                        label = { Text(rating) },
                        enabled = isEditable
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = notes,
                onValueChange = onNotesChanged,
                label = { Text("ملاحظات خاصة بالمادة (اختياري)") },
                modifier = Modifier.fillMaxWidth(),
                enabled = isEditable,
                singleLine = true
            )
        }
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
