package sa.gheras.edutrack.ui.supervisor.pickup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import sa.gheras.edutrack.data.remote.dto.PickupStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupervisorHandoverScreen(
    requestId: String,
    viewModel: SupervisorGateViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val request = uiState.requests.firstOrNull { it.id == requestId }
    var receiverIdNo by remember(requestId) { mutableStateOf("") }
    var receiverName by remember(requestId) { mutableStateOf(request?.delegateName ?: "") }
    var receiverPhone by remember(requestId) { mutableStateOf(request?.delegatePhone ?: "") }
    var receiverRelation by remember(requestId) {
        mutableStateOf(request?.delegateRelation ?: if (request?.delegateId != null) "مفوض" else "ولي أمر")
    }
    var showFailureDialog by remember { mutableStateOf(false) }
    var failureReason by remember { mutableStateOf("") }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("تأكيد تسليم الطالب", fontWeight = FontWeight.Bold)
                        request?.studentName?.let { name ->
                            Text(
                                text = name,
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier
    ) { innerPadding ->
        when {
            request == null && uiState.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
            request == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "لا يمكن العثور على طلب التسليم",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "بيانات الطالب",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            DetailRow(label = "الطالب", value = request.studentName ?: "-")
                            request.roomName?.takeIf { it.isNotBlank() }?.let { room ->
                                DetailRow(label = "الفصل", value = room)
                            }
                            DetailRow(label = "وقت الاستلام", value = request.pickupTime ?: "-")
                        }
                    }

                    Card(
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "التحقق من هوية المستلم",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            DetailRow(
                                label = "المستلم",
                                value = request.delegateName?.takeIf { it.isNotBlank() }
                                    ?.let { "مفوض: $it" } ?: "ولي الأمر"
                            )
                            OutlinedTextField(
                                value = receiverIdNo,
                                onValueChange = { receiverIdNo = it },
                                label = { Text("رقم هوية المستلم المطابق") },
                                singleLine = true,
                                enabled = !uiState.isLoading,
                                textStyle = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = receiverName,
                                onValueChange = { receiverName = it },
                                label = { Text("اسم المستلم الفعلي") },
                                singleLine = true,
                                enabled = !uiState.isLoading,
                                textStyle = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = receiverPhone,
                                onValueChange = { receiverPhone = it },
                                label = { Text("رقم جوال المستلم") },
                                singleLine = true,
                                enabled = !uiState.isLoading,
                                textStyle = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = receiverRelation,
                                onValueChange = { receiverRelation = it },
                                label = { Text("صلة القرابة / الصفة") },
                                singleLine = true,
                                enabled = !uiState.isLoading,
                                textStyle = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    Button(
                        onClick = {
                            viewModel.handover(
                                requestId,
                                receiverIdNo.trim(),
                                receiverName.trim(),
                                receiverPhone.trim(),
                                receiverRelation.trim()
                            ) { onNavigateBack() }
                        },
                        enabled = !uiState.isLoading &&
                            receiverIdNo.isNotBlank() &&
                            receiverName.isNotBlank() &&
                            receiverPhone.isNotBlank() &&
                            receiverRelation.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (uiState.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = 8.dp),
                                strokeWidth = 2.dp
                            )
                        }
                        Text("تأكيد التسليم والانصراف")
                    }

                    OutlinedButton(
                        onClick = { showFailureDialog = true },
                        enabled = !uiState.isLoading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "تعذر التسليم / بلاغ",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                if (showFailureDialog) {
                    AlertDialog(
                        onDismissRequest = { showFailureDialog = false },
                        title = {
                            Text("سبب تعذر التسليم", fontWeight = FontWeight.Bold)
                        },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "يرجى تحديد سبب تعذر تسليم الطالب (إلزامي):",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                OutlinedTextField(
                                    value = failureReason,
                                    onValueChange = { failureReason = it },
                                    label = { Text("السبب") },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showFailureDialog = false
                                    viewModel.reportFailure(requestId, failureReason.trim()) { onNavigateBack() }
                                },
                                enabled = failureReason.isNotBlank()
                            ) {
                                Text(
                                    text = "تسجيل البلاغ",
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showFailureDialog = false }) {
                                Text("إلغاء")
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier.fillMaxWidth()
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp)
        )
    }
}
