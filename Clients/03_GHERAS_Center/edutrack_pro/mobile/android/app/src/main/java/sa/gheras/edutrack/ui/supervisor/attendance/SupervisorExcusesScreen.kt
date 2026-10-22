package sa.gheras.edutrack.ui.supervisor.attendance

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Attachment
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import sa.gheras.edutrack.data.remote.dto.AttendanceExcuseDto
import sa.gheras.edutrack.ui.common.EmptyView
import sa.gheras.edutrack.ui.common.LoadingView
import sa.gheras.edutrack.ui.common.Num
import sa.gheras.edutrack.ui.theme.AbsentRed
import sa.gheras.edutrack.ui.theme.ExcusedBlue
import sa.gheras.edutrack.ui.theme.PresentGreen
import sa.gheras.edutrack.ui.theme.WarningAmber

private data class ReviewDialogState(
    val excuse: AttendanceExcuseDto,
    val action: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupervisorExcusesScreen(
    viewModel: SupervisorExcusesViewModel,
    onNavigateBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var activeDialogState by remember { mutableStateOf<ReviewDialogState?>(null) }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearMessages()
        }
    }

    LaunchedEffect(state.successMessage) {
        state.successMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearMessages()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("مراجعة أعذار الغياب", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    if (onNavigateBack != null) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "تحديث")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val tabs = listOf(
                "قيد المراجعة" to "pending",
                "المقبولة" to "approved",
                "المرفوضة" to "rejected",
                "تحتاج استكمال" to "needs_info",
                "الكل" to null
            )

            TabRow(selectedTabIndex = selectedTabIndex) {
                tabs.forEachIndexed { index, (label, status) ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = {
                            selectedTabIndex = index
                            viewModel.setFilter(status)
                        },
                        text = {
                            Text(
                                text = label,
                                fontWeight = if (selectedTabIndex == index) FontWeight.Bold else FontWeight.Normal,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    )
                }
            }

            if (state.isLoading && state.excuses.isEmpty()) {
                LoadingView()
            } else if (state.excuses.isEmpty()) {
                EmptyView(
                    message = "لا توجد أعذار مطابقة لهذا التبويب",
                    icon = Icons.Default.FactCheck
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(state.excuses, key = { it.id }) { excuse ->
                        SupervisorExcuseCard(
                            excuse = excuse,
                            onActionClick = { action ->
                                activeDialogState = ReviewDialogState(excuse, action)
                            }
                        )
                    }
                }
            }
        }
    }

    activeDialogState?.let { dialogState ->
        ReviewExcuseActionDialog(
            excuse = dialogState.excuse,
            action = dialogState.action,
            isProcessing = state.isProcessingAction,
            onDismiss = { activeDialogState = null },
            onConfirm = { notes ->
                viewModel.reviewExcuse(
                    excuseId = dialogState.excuse.id,
                    action = dialogState.action,
                    notes = notes,
                    onSuccess = { activeDialogState = null }
                )
            }
        )
    }
}

@Composable
fun SupervisorExcuseCard(
    excuse: AttendanceExcuseDto,
    onActionClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header: Student Name + Room + Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = excuse.studentName ?: "طالب",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    excuse.roomName?.let { room ->
                        Text(
                            text = "القاعة: $room",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                ExcuseStatusBadge(status = excuse.status)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Date of absence
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "تاريخ الغياب: ",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Num(text = excuse.excuseDate, style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Reason
            Text(
                text = "سبب العذر: ${excuse.reasonText}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Attachment if present
            if (!excuse.attachmentStorageKey.isNullOrBlank() || !excuse.attachmentUrl.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                AssistChip(
                    onClick = {},
                    label = { Text("مستند / تقرير طبي مرفق", style = MaterialTheme.typography.labelSmall) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Attachment,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            }

            // Supervisor notes if exists
            if (!excuse.supervisorNotes.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(10.dp)
                ) {
                    Column {
                        Text(
                            text = "ملاحظات المشرفة:",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = excuse.supervisorNotes,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        excuse.reviewedByName?.let { reviewer ->
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "بواسطة: $reviewer",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Action Buttons (Only when pending)
            if (excuse.status == "pending") {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { onActionClick("approve") },
                        colors = ButtonDefaults.buttonColors(containerColor = PresentGreen),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("قبول", fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = { onActionClick("needs_info") },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ExcusedBlue),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Help, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("استكمال")
                    }

                    OutlinedButton(
                        onClick = { onActionClick("reject") },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AbsentRed),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("رفض")
                    }
                }
            }
        }
    }
}

@Composable
fun ExcuseStatusBadge(status: String) {
    val (bgColor, textColor, label) = when (status) {
        "approved" -> Triple(PresentGreen.copy(alpha = 0.15f), PresentGreen, "مقبول")
        "rejected" -> Triple(AbsentRed.copy(alpha = 0.15f), AbsentRed, "مرفوض")
        "needs_info" -> Triple(ExcusedBlue.copy(alpha = 0.15f), ExcusedBlue, "يحتاج استكمال")
        else -> Triple(WarningAmber.copy(alpha = 0.15f), WarningAmber, "قيد المراجعة")
    }

    Box(
        modifier = Modifier
            .background(color = bgColor, shape = RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            color = textColor,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun ReviewExcuseActionDialog(
    excuse: AttendanceExcuseDto,
    action: String,
    isProcessing: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit
) {
    var notesText by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    val (title, description, isNotesMandatory, buttonColor, buttonText) = when (action) {
        "approve" -> Tuple5(
            "قبول عذر الغياب",
            "سيتم اعتماد غياب الطالب بعذر رسمي، وتحديث سجل الحضور آلياً وإشعار ولي الأمر بالقبول.",
            false,
            PresentGreen,
            "تأكيد القبول"
        )
        "reject" -> Tuple5(
            "رفض عذر الغياب",
            "سيتم رفض العذر وإشعار ولي الأمر بالرفض. يرجى توضيح سبب الرفض:",
            true,
            AbsentRed,
            "تأكيد الرفض"
        )
        else -> Tuple5(
            "طلب استكمال مستندات العذر",
            "سيُطلب من ولي الأمر استكمال النواقص أو إرفاق تقارير طبية إضافية. يرجى توضيح المطلوب بدقة:",
            true,
            ExcusedBlue,
            "إرسال طلب الاستكمال"
        )
    }

    LaunchedEffect(Unit) {
        try {
            focusRequester.requestFocus()
        } catch (_: Exception) {
            // Focus Requester safety per [C-85]
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isProcessing) onDismiss() },
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "الطالب: ${excuse.studentName ?: "غير محدد"} (${excuse.excuseDate})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = notesText,
                    onValueChange = { notesText = it },
                    label = {
                        Text(if (isNotesMandatory) "ملاحظات المشرفة (إلزامية)" else "ملاحظات المشرفة (اختيارية)")
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    minLines = 3,
                    isError = isNotesMandatory && notesText.trim().isEmpty(),
                    enabled = !isProcessing
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(notesText.ifBlank { null }) },
                enabled = !isProcessing && (!isNotesMandatory || notesText.trim().isNotEmpty()),
                colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
                shape = RoundedCornerShape(8.dp)
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(buttonText, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isProcessing
            ) {
                Text("إلغاء")
            }
        }
    )
}

private data class Tuple5<A, B, C, D, E>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
    val fifth: E
)
