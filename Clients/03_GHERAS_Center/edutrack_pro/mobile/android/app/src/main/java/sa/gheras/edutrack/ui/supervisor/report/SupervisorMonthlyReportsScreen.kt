package sa.gheras.edutrack.ui.supervisor.report

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Publish
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import sa.gheras.edutrack.data.remote.dto.MonthlyReportDto
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupervisorMonthlyReportsScreen(
    viewModel: SupervisorMonthlyReportsViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var revisionDialogReportId by remember { mutableStateOf<String?>(null) }
    var revisionNotesText by remember { mutableStateOf("") }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("مراجعة التقارير الشهرية", fontWeight = FontWeight.Bold) },
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
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Month Selector
            SupervisorMonthFilter(
                currentMonthStr = state.selectedMonth,
                onMonthChanged = { viewModel.setMonthFilter(it) }
            )

            // Status Filter Chips
            StatusFilterRow(
                selectedStatus = state.selectedStatus,
                onStatusSelected = { viewModel.setStatusFilter(it) }
            )

            if (state.isLoading && state.reports.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (state.reports.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "لا توجد تقارير شهرية مطابقة للمحددات",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Check if any report is approved to offer batch publish
                    val approvedList = state.reports.filter { it.status == "approved" }
                    if (approvedList.isNotEmpty()) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFE0F2F1))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "يوجد ${approvedList.size} تقرير معتمد وجاهز للنشر",
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF00695C)
                                        )
                                        Text(
                                            "يمكنك النشر دفعة واحدة وإشعار كافة أولياء الأمور",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color(0xFF004D40)
                                        )
                                    }
                                    Button(
                                        onClick = {
                                            val roomId = approvedList.first().roomId
                                            viewModel.batchPublish(roomId)
                                        },
                                        enabled = !state.isProcessing,
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00796B))
                                    ) {
                                        Icon(Icons.Default.Publish, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("نشر الكل")
                                    }
                                }
                            }
                        }
                    }

                    items(state.reports, key = { it.id }) { report ->
                        SupervisorReportCard(
                            report = report,
                            isProcessing = state.isProcessing,
                            onApprove = { viewModel.approveReport(report.id) },
                            onRequestRevision = {
                                revisionDialogReportId = report.id
                                revisionNotesText = ""
                            },
                            onPublish = { viewModel.publishReport(report.id) }
                        )
                    }
                }
            }
        }
    }

    // Revision notes dialog
    if (revisionDialogReportId != null) {
        AlertDialog(
            onDismissRequest = { revisionDialogReportId = null },
            title = { Text("طلب تعديل التقرير الشهري") },
            text = {
                Column {
                    Text("يرجى كتابة الملاحظات والأسباب الموجهة للمعلمة لإعادة صياغة التقرير:")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = revisionNotesText,
                        onValueChange = { revisionNotesText = it },
                        label = { Text("ملاحظات التعديل") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val repId = revisionDialogReportId ?: return@Button
                        viewModel.requestRevision(repId, revisionNotesText)
                        revisionDialogReportId = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("إرسال طلب التعديل")
                }
            },
            dismissButton = {
                TextButton(onClick = { revisionDialogReportId = null }) {
                    Text("إلغاء")
                }
            }
        )
    }
}

@Composable
private fun SupervisorReportCard(
    report: MonthlyReportDto,
    isProcessing: Boolean,
    onApprove: () -> Unit,
    onRequestRevision: () -> Unit,
    onPublish: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        report.studentName ?: "طالب #${report.studentId.take(6)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "الفصل: ${report.roomName ?: report.roomId.take(6)} | المعلمة: ${report.teacherName ?: "معلمة"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ReportBadge(report.status)
            }

            Spacer(Modifier.height(8.dp))

            // Quick Stats summary
            val stats = report.attendanceStats
            if (stats != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("الحضور: ${stats.attendanceRate}%", style = MaterialTheme.typography.labelMedium, color = Color(0xFF1565C0))
                    Text("الغياب: ${stats.absentDays} يوم", style = MaterialTheme.typography.labelMedium, color = Color(0xFFC62828))
                    Text("المواد: ${report.subjectRatings.size}", style = MaterialTheme.typography.labelMedium)
                }
            }

            // Expand / Collapse details
            IconButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "طي التفاصيل" else "عرض التفاصيل"
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Ratings list
                    Text("تقييم المواد:", fontWeight = FontWeight.Bold)
                    report.subjectRatings.forEach { r ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(r.subject, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                r.rating + (if (!r.notes.isNullOrBlank()) " (${r.notes})" else ""),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    if (!report.strengths.isNullOrBlank()) {
                        Text("نقاط القوة: ${report.strengths}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (!report.improvements.isNullOrBlank()) {
                        Text("مجالات التحسين: ${report.improvements}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (!report.recommendations.isNullOrBlank()) {
                        Text("التوصيات: ${report.recommendations}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (!report.revisionNotes.isNullOrBlank()) {
                        Text("ملاحظات التعديل الحالية: ${report.revisionNotes}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }

                    Spacer(Modifier.height(8.dp))
                }
            }

            // Action Buttons
            when (report.status) {
                "pending_approval" -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onRequestRevision,
                            modifier = Modifier.weight(1f),
                            enabled = !isProcessing
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("طلب تعديل")
                        }

                        Button(
                            onClick = onApprove,
                            modifier = Modifier.weight(1f),
                            enabled = !isProcessing,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("اعتماد")
                        }
                    }
                }
                "approved" -> {
                    Button(
                        onClick = onPublish,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isProcessing,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00796B))
                    ) {
                        Icon(Icons.Default.Publish, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("نشر التقرير الآن لولي الأمر")
                    }
                }
                "published" -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.DoneAll, contentDescription = null, tint = Color(0xFF00796B), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("تم النشر ومتاح لولي الأمر", color = Color(0xFF00796B), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportBadge(status: String) {
    val (label, bg, fg) = when (status) {
        "pending_approval" -> Triple("قيد المراجعة", Color(0xFFFFF3E0), Color(0xFFE65100))
        "revision_requested" -> Triple("مطلوب تعديل", Color(0xFFFFEBEE), Color(0xFFC62828))
        "approved" -> Triple("معتمد", Color(0xFFE8F5E9), Color(0xFF2E7D32))
        "published" -> Triple("منشور", Color(0xFFE0F2F1), Color(0xFF00695C))
        else -> Triple("مسودة", Color(0xFFEDE7F6), Color(0xFF512DA8))
    }

    Card(colors = CardDefaults.cardColors(containerColor = bg)) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = fg
        )
    }
}

@Composable
private fun SupervisorMonthFilter(
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
private fun StatusFilterRow(
    selectedStatus: String?,
    onStatusSelected: (String?) -> Unit
) {
    val filters = listOf(
        Pair("pending_approval", "قيد المراجعة"),
        Pair("approved", "معتمد"),
        Pair("published", "منشور"),
        Pair("revision_requested", "مطلوب تعديل"),
        Pair(null, "الكل")
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        filters.forEach { (status, label) ->
            FilterChip(
                selected = selectedStatus == status,
                onClick = { onStatusSelected(status) },
                label = { Text(label) }
            )
        }
    }
}
