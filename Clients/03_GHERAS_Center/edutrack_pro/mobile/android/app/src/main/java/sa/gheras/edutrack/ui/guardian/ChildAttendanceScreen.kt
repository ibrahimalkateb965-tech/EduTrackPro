package sa.gheras.edutrack.ui.guardian

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Attachment
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import sa.gheras.edutrack.data.entity.AttendanceExcuseEntity
import sa.gheras.edutrack.ui.common.EmptyView
import sa.gheras.edutrack.ui.common.LoadingView
import sa.gheras.edutrack.ui.common.Num
import sa.gheras.edutrack.ui.theme.PresentGreen
import sa.gheras.edutrack.ui.theme.WarningAmber
import java.io.File
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChildAttendanceScreen(
    viewModel: ChildAttendanceViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    var showSubmitDialog by remember { mutableStateOf(false) }
    var excuseToPatch by remember { mutableStateOf<AttendanceExcuseEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("سجل الحضور والأعذار", fontWeight = FontWeight.Bold)
                        Text(
                            text = state.studentName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showSubmitDialog = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("تقديم عذر غياب", fontWeight = FontWeight.Bold) },
                containerColor = MaterialTheme.colorScheme.primary
            )
        },
        modifier = modifier
    ) { innerPadding ->
        if (state.isLoading) {
            LoadingView(modifier = Modifier.padding(innerPadding))
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // Tab Selection: Records vs Excuses
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("كشف الحضور (${state.records.size})", fontWeight = FontWeight.SemiBold) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("الأعذار المقدمة (${state.excuses.size})", fontWeight = FontWeight.SemiBold) }
                    )
                }

                if (selectedTab == 0) {
                    AttendanceRecordsList(
                        state = state,
                        onOpenExcuseDialog = { showSubmitDialog = true }
                    )
                } else {
                    AttendanceExcusesList(
                        excuses = state.excuses,
                        onPatchExcuse = { excuseToPatch = it }
                    )
                }
            }
        }
    }

    if (showSubmitDialog) {
        SubmitExcuseDialog(
            isSubmitting = state.isSubmittingExcuse,
            errorMessage = state.excuseError,
            onDismiss = {
                viewModel.clearError()
                showSubmitDialog = false
            },
            onSubmit = { date, reason, localPath ->
                viewModel.submitExcuse(date, reason, localPath) {
                    showSubmitDialog = false
                }
            }
        )
    }

    excuseToPatch?.let { excuse ->
        PatchExcuseDialog(
            excuse = excuse,
            isSubmitting = state.isSubmittingExcuse,
            errorMessage = state.excuseError,
            onDismiss = {
                viewModel.clearError()
                excuseToPatch = null
            },
            onPatch = { reason, localPath ->
                viewModel.patchExcuse(excuse.id, reason, localPath) {
                    excuseToPatch = null
                }
            }
        )
    }
}

@Composable
fun AttendanceRecordsList(
    state: ChildAttendanceUiState,
    onOpenExcuseDialog: () -> Unit
) {
    if (state.records.isEmpty()) {
        EmptyView(
            message = "لا توجد سجلات حضور مسجلة في نافذة الـ 60 يوماً",
            icon = Icons.Default.FactCheck
        )
    } else {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            // Summary Pills Card
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = Num.formatInt(state.presentCount),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = PresentGreen
                            )
                            Text("حاضر", style = MaterialTheme.typography.bodySmall)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = Num.formatInt(state.absentCount),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text("غائب", style = MaterialTheme.typography.bodySmall)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = Num.formatInt(state.lateCount),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = WarningAmber
                            )
                            Text("متأخر", style = MaterialTheme.typography.bodySmall)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = Num.formatInt(state.excusedCount),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = PresentGreen
                            )
                            Text("مستأذن / بعذر", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            items(state.records, key = { it.id }) { record ->
                val (statusColor, statusBg, labelText) = when (record.status) {
                    "حاضر" -> Triple(PresentGreen, PresentGreen.copy(alpha = 0.15f), "حاضر")
                    "غائب" -> Triple(MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.error.copy(alpha = 0.15f), "غائب")
                    "متأخر" -> Triple(WarningAmber, WarningAmber.copy(alpha = 0.15f), "متأخر")
                    "غياب بعذر" -> Triple(PresentGreen, PresentGreen.copy(alpha = 0.15f), "غياب بعذر معتمد")
                    else -> Triple(MaterialTheme.colorScheme.outline, MaterialTheme.colorScheme.surfaceVariant, record.status)
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = record.date.toString(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (!record.note.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = record.note,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Card(
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(containerColor = statusBg)
                        ) {
                            Text(
                                text = labelText,
                                style = MaterialTheme.typography.labelLarge,
                                color = statusColor,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AttendanceExcusesList(
    excuses: List<AttendanceExcuseEntity>,
    onPatchExcuse: (AttendanceExcuseEntity) -> Unit
) {
    if (excuses.isEmpty()) {
        EmptyView(
            message = "لم يتم تقديم أي أعذار غياب بعد",
            icon = Icons.Default.MedicalServices
        )
    } else {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(excuses, key = { it.id }) { excuse ->
                val (statusColor, statusBg, statusTitle) = when (excuse.status) {
                    "approved" -> Triple(PresentGreen, PresentGreen.copy(alpha = 0.15f), "معتمد")
                    "rejected" -> Triple(MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.error.copy(alpha = 0.15f), "مرفوض")
                    "needs_info" -> Triple(WarningAmber, WarningAmber.copy(alpha = 0.15f), "مطلوب استكمال")
                    else -> Triple(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), "قيد المراجعة")
                }

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
                            Text(
                                text = "تاريخ الغياب: ${excuse.excuseDate}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Card(
                                shape = RoundedCornerShape(8.dp),
                                colors = CardDefaults.cardColors(containerColor = statusBg)
                            ) {
                                Text(
                                    text = statusTitle,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = statusColor,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "السبب: ${excuse.reasonText}",
                            style = MaterialTheme.typography.bodyMedium
                        )

                        if (!excuse.supervisorNotes.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "ملاحظات المشرفة: ${excuse.supervisorNotes}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        if (excuse.status == "needs_info") {
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = { onPatchExcuse(excuse) },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text("استكمال العذر", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SubmitExcuseDialog(
    isSubmitting: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSubmit: (LocalDate, String, String?) -> Unit
) {
    val context = LocalContext.current
    var reasonText by remember { mutableStateOf("") }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var attachedPath by remember { mutableStateOf<String?>(null) }
    var pickerError by remember { mutableStateOf<String?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val cr = context.contentResolver
                val mime = cr.getType(uri) ?: ""
                val uriStr = uri.toString().lowercase()
                val isImageOrPdf = mime.startsWith("image/") || mime == "application/pdf" ||
                        uriStr.endsWith(".pdf") || uriStr.endsWith(".jpg") || uriStr.endsWith(".png") || uriStr.endsWith(".jpeg")
                if (!isImageOrPdf) {
                    pickerError = "يرجى اختيار صورة (JPG, PNG) أو ملف PDF فقط"
                    return@rememberLauncherForActivityResult
                }
                val ext = when {
                    mime == "application/pdf" || uriStr.endsWith(".pdf") -> "pdf"
                    mime == "image/png" || uriStr.endsWith(".png") -> "png"
                    else -> "jpg"
                }
                val destFile = File(context.cacheDir, "excuse_${System.currentTimeMillis()}.$ext")
                cr.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                if (destFile.length() > 25 * 1024 * 1024) {
                    destFile.delete()
                    pickerError = "حجم الملف يتجاوز الحد الأقصى 25 ميجابايت"
                    return@rememberLauncherForActivityResult
                }
                attachedPath = destFile.absolutePath
                pickerError = null
            } catch (_: Exception) {
                pickerError = "تعذر قراءة أو حفظ الملف المرفق"
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تقديم عذر غياب", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    text = "تاريخ العذر: $selectedDate",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = reasonText,
                    onValueChange = { reasonText = it },
                    label = { Text("سبب الغياب (5 أحرف على الأقل)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    isError = errorMessage != null
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (attachedPath != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Attachment, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = attachedPath!!.substringAfterLast("/"),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1
                            )
                        }
                        IconButton(onClick = { attachedPath = null }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "حذف المرفق", modifier = Modifier.size(16.dp))
                        }
                    }
                } else {
                    OutlinedButton(
                        onClick = { filePicker.launch("*/*") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Attachment, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("إرفاق تقرير طبي أو مستند (اختياري)", style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (pickerError != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = pickerError!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(selectedDate, reasonText, attachedPath) },
                enabled = !isSubmitting && reasonText.trim().length >= 5
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("إرسال العذر")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء")
            }
        }
    )
}

@Composable
fun PatchExcuseDialog(
    excuse: AttendanceExcuseEntity,
    isSubmitting: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onPatch: (String, String?) -> Unit
) {
    val context = LocalContext.current
    var reasonText by remember { mutableStateOf(excuse.reasonText) }
    var attachedPath by remember { mutableStateOf(excuse.attachmentStorageKey) }
    var pickerError by remember { mutableStateOf<String?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val cr = context.contentResolver
                val mime = cr.getType(uri) ?: ""
                val uriStr = uri.toString().lowercase()
                val isImageOrPdf = mime.startsWith("image/") || mime == "application/pdf" ||
                        uriStr.endsWith(".pdf") || uriStr.endsWith(".jpg") || uriStr.endsWith(".png") || uriStr.endsWith(".jpeg")
                if (!isImageOrPdf) {
                    pickerError = "يرجى اختيار صورة (JPG, PNG) أو ملف PDF فقط"
                    return@rememberLauncherForActivityResult
                }
                val ext = when {
                    mime == "application/pdf" || uriStr.endsWith(".pdf") -> "pdf"
                    mime == "image/png" || uriStr.endsWith(".png") -> "png"
                    else -> "jpg"
                }
                val destFile = File(context.cacheDir, "excuse_${System.currentTimeMillis()}.$ext")
                cr.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                if (destFile.length() > 25 * 1024 * 1024) {
                    destFile.delete()
                    pickerError = "حجم الملف يتجاوز الحد الأقصى 25 ميجابايت"
                    return@rememberLauncherForActivityResult
                }
                attachedPath = destFile.absolutePath
                pickerError = null
            } catch (_: Exception) {
                pickerError = "تعذر قراءة أو حفظ الملف المرفق"
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("استكمال بيانات العذر", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    text = "تاريخ الغياب: ${excuse.excuseDate}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (!excuse.supervisorNotes.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "ملاحظات المشرفة: ${excuse.supervisorNotes}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = reasonText,
                    onValueChange = { reasonText = it },
                    label = { Text("تحديث سبب الغياب / التوضيح الإضافي") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (attachedPath != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Attachment, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = attachedPath!!.substringAfterLast("/"),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1
                            )
                        }
                        IconButton(onClick = { attachedPath = null }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "حذف المرفق", modifier = Modifier.size(16.dp))
                        }
                    }
                } else {
                    OutlinedButton(
                        onClick = { filePicker.launch("*/*") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Attachment, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("إرفاق تقرير طبي أو مستند (اختياري)", style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (pickerError != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = pickerError!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onPatch(reasonText, attachedPath) },
                enabled = !isSubmitting && reasonText.trim().isNotBlank()
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("إعادة الإرسال")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء")
            }
        }
    )
}
