package sa.gheras.edutrack.ui.teacher

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import sa.gheras.edutrack.ui.common.EmptyView
import sa.gheras.edutrack.ui.theme.PresentGreen
import sa.gheras.edutrack.ui.theme.WarningAmber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttendanceSheetScreen(
    viewModel: AttendanceSheetViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    var showDiscardDialog by remember { mutableStateOf(false) }

    val handleBack = {
        if (state.isDirty) {
            showDiscardDialog = true
        } else {
            onNavigateBack()
        }
    }

    BackHandler(onBack = handleBack)

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("تجاهل التغييرات؟") },
            text = { Text("لديك تعديلات غير محفوظة، هل تريد الخروج وتجاهلها؟") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardDialog = false
                        onNavigateBack()
                    }
                ) {
                    Text("تجاهل", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text("إلغاء")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("كشف الحضور", fontWeight = FontWeight.Bold)
                            if (state.isAlreadyApproved) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(PresentGreen.copy(alpha = 0.15f))
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "معتمد",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = PresentGreen,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        Text(
                            text = "${state.roomName} — ${state.date}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = handleBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                    }
                }
            )
        },
        bottomBar = {
            SurfaceBottomBar {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (!state.canApprove && state.drafts.isNotEmpty()) {
                        Text(
                            text = "يجب تحديد حالة جميع الطلاب لتفعيل اعتماد الكشف",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // 1. Save Draft Button
                        OutlinedButton(
                            onClick = { viewModel.saveDraft(onSaved = onNavigateBack) },
                            enabled = !state.isSaving && !state.isApproving && state.canSaveDraft,
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (state.isSaving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("حفظ كمسودة", fontWeight = FontWeight.SemiBold)
                            }
                        }

                        // 2. Approve Room Day Button
                        Button(
                            onClick = { viewModel.approve(onApproved = onNavigateBack) },
                            enabled = !state.isSaving && !state.isApproving && state.canApprove,
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (state.isApproving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("اعتماد الكشف", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        },
        modifier = modifier
    ) { innerPadding ->
        if (state.drafts.isEmpty()) {
            EmptyView(
                message = "لا يوجد طلاب في هذه القاعة",
                modifier = Modifier.padding(innerPadding)
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                items(state.drafts, key = { it.student.id }) { draft ->
                    StudentAttendanceRow(
                        draft = draft,
                        onStatusChanged = { status -> viewModel.updateStatus(draft.student.id, status) }
                    )
                }
            }
        }
    }
}

@Composable
fun StudentAttendanceRow(
    draft: StudentAttendanceDraft,
    onStatusChanged: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val statuses = listOf("حاضر", "غائب", "متأخر", "مستأذن")

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = draft.student.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                if (draft.isExcusedApproved || draft.status == "غياب بعذر") {
                    SuggestionChip(
                        onClick = {},
                        label = { Text("غياب بعذر معتمد", style = MaterialTheme.typography.labelSmall) },
                        icon = { Icon(Icons.Default.MedicalServices, contentDescription = null, modifier = Modifier.size(14.dp)) },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = PresentGreen.copy(alpha = 0.15f),
                            labelColor = PresentGreen,
                            iconContentColor = PresentGreen
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                statuses.forEach { status ->
                    val isSelected = draft.status == status
                    val chipColor = when (status) {
                        "حاضر" -> PresentGreen
                        "غائب" -> MaterialTheme.colorScheme.error
                        "متأخر" -> WarningAmber
                        else -> MaterialTheme.colorScheme.outline
                    }

                    FilterChip(
                        selected = isSelected,
                        onClick = { onStatusChanged(status) },
                        label = { Text(status) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = chipColor.copy(alpha = 0.2f),
                            selectedLabelColor = chipColor
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
fun SurfaceBottomBar(content: @Composable () -> Unit) {
    androidx.compose.material3.Surface(
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}
