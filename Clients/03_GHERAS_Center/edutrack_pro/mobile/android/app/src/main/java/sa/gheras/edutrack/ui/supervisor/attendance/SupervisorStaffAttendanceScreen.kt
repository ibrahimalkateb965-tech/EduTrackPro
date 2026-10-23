package sa.gheras.edutrack.ui.supervisor.attendance

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import sa.gheras.edutrack.data.remote.dto.StaffAttendanceRecordDto

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupervisorStaffAttendanceScreen(
    viewModel: SupervisorStaffAttendanceViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.userMessage, state.errorMessage) {
        state.userMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissMessages()
        }
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissMessages()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("حضور وانصراف الكادر") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.loadAttendance() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "تحديث")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Date Header & Summary Box
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "تاريخ اليوم: ${state.selectedDate}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (state.isLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            MetricPill("الكادر", "${state.totalCount}", MaterialTheme.colorScheme.primary)
                            MetricPill("حاضر", "${state.presentCount}", Color(0xFF2E7D32))
                            MetricPill("متأخر", "${state.lateCount}", Color(0xFFFF9800))
                            MetricPill("غائب", "${state.absentCount}", Color(0xFFC62828))
                        }
                    }
                }
            }

            // 2. Section Title
            item {
                Text(
                    text = "سجل الكادر التعليمي والإداري",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            // 3. List of Staff Records
            if (state.records.isEmpty() && !state.isLoading) {
                item {
                    Text(
                        text = "لا توجد سجلات مسجلة لهذا التاريخ حتى الآن",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp)
                    )
                }
            } else {
                items(state.records) { record ->
                    StaffSupervisorCard(
                        record = record,
                        onOverrideClick = { viewModel.openOverrideDialog(record) }
                    )
                }
            }
        }
    }

    // Manual Override Dialog
    state.selectedRecordForOverride?.let { target ->
        AlertDialog(
            onDismissRequest = { viewModel.closeOverrideDialog() },
            title = {
                Text("تعديل حضور: ${target.staffName ?: "الموظفة"}")
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("اختر الحالة الجديدة:")
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                    ) {
                        listOf("حاضر", "متأخر", "مستأذن", "غائب").forEach { st ->
                            FilterChip(
                                selected = state.overrideStatus == st,
                                onClick = { viewModel.setOverrideStatus(st) },
                                label = { Text(st) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = state.overrideReason,
                        onValueChange = { viewModel.setOverrideReason(it) },
                        label = { Text("سبب التعديل / العذر المعتمد *") },
                        placeholder = { Text("مثال: عذر مرضي معتمد، إذن إداري") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        maxLines = 3
                    )

                    OutlinedTextField(
                        value = state.deductionAmount,
                        onValueChange = { viewModel.setDeductionAmount(it) },
                        label = { Text("قيمة الخصم (ريال)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.submitOverride() },
                    enabled = !state.isOverriding
                ) {
                    if (state.isOverriding) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                    } else {
                        Text("حفظ التعديل")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.closeOverrideDialog() }) {
                    Text("إلغاء")
                }
            }
        )
    }
}

@Composable
private fun MetricPill(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StaffSupervisorCard(
    record: StaffAttendanceRecordDto,
    onOverrideClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = record.staffName ?: "موظفة",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = record.roleTitle ?: "معلمة",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = when (record.status) {
                        "حاضر" -> Color(0xFFE8F5E9)
                        "متأخر" -> Color(0xFFFFF3E0)
                        "مستأذن" -> Color(0xFFE3F2FD)
                        else -> Color(0xFFFFEBEE)
                    }
                ) {
                    Text(
                        text = record.status,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = when (record.status) {
                            "حاضر" -> Color(0xFF2E7D32)
                            "متأخر" -> Color(0xFFEF6C00)
                            "مستأذن" -> Color(0xFF1565C0)
                            else -> Color(0xFFC62828)
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            val inTime = record.checkInTime?.substringAfter("T")?.take(5) ?: "--:--"
            val outTime = record.checkOutTime?.substringAfter("T")?.take(5) ?: "--:--"
            val dist = record.checkInDistance?.toInt() ?: 0

            Text(
                text = "دخول: $inTime (المسافة: ${dist}م) | انصراف: $outTime",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (record.isManualOverride) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "معدل يدوياً: ${record.overrideReason ?: "بواسطة الإشراف"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF1565C0),
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(
                    onClick = onOverrideClick,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("تعديل الحالة", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
