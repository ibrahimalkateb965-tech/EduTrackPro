package sa.gheras.edutrack.ui.guardian.pickup

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import sa.gheras.edutrack.data.remote.dto.PickupRequestDto
import sa.gheras.edutrack.data.remote.dto.PickupStatus
import sa.gheras.edutrack.data.repo.PickupRepository
import sa.gheras.edutrack.ui.theme.PresentGreen

private val trackingSteps = listOf(
    PickupStatus.NOTIFIED to "تم إرسال الطلب",
    PickupStatus.IN_PREP to "جاري تجهيز الطالب",
    PickupStatus.READY to "الطالب جاهز عند البوابة"
)

private val terminalStatusLabels = mapOf(
    PickupStatus.COMPLETED to "تم التسليم بنجاح",
    PickupStatus.CANCELLED to "ملغي",
    PickupStatus.FAILED to "فشل التسليم"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickupTrackingScreen(
    requestId: String,
    pickupRepository: PickupRepository,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var request by remember { mutableStateOf<PickupRequestDto?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isCancelling by remember { mutableStateOf(false) }
    var showCancelDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(requestId) {
        while (isActive) {
            pickupRepository.getRequestById(requestId)
                .onSuccess { fetched ->
                    request = fetched
                    errorMessage = null
                    // K3: Stop polling once the request reaches a terminal status
                    if (fetched.status in PickupStatus.TERMINAL_STATUSES) {
                        return@LaunchedEffect
                    }
                }
                .onFailure { error ->
                    errorMessage = error.message ?: "فشل تحديث حالة الطلب"
                }
            delay(5000)
        }
    }

    LaunchedEffect(errorMessage) {
        errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            errorMessage = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("متابعة طلب الاستلام", fontWeight = FontWeight.Bold)
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
        val activeRequest = request
        if (activeRequest == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                // Status stepper
                StepperCard(activeRequest)

                // Terminal status card (when present)
                terminalStatusLabels[activeRequest.status]?.let { terminalLabel ->
                    val (labelColor, labelBg) = when (activeRequest.status) {
                        PickupStatus.COMPLETED -> PresentGreen to PresentGreen.copy(alpha = 0.15f)
                        PickupStatus.FAILED -> MaterialTheme.colorScheme.error to MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                        else -> MaterialTheme.colorScheme.outline to MaterialTheme.colorScheme.surfaceVariant
                    }
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = labelBg)
                    ) {
                        Text(
                            text = terminalLabel,
                            style = MaterialTheme.typography.titleMedium,
                            color = labelColor,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        )
                    }
                }

                // Details card
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
                        DetailRow(label = "الطالب", value = activeRequest.studentName ?: "-")
                        DetailRow(
                            label = "المستلم",
                            value = activeRequest.delegateName?.takeIf { it.isNotBlank() } ?: "ولي الأمر"
                        )
                        DetailRow(label = "وقت الطلب", value = activeRequest.pickupTime ?: "-")
                        DetailRow(
                            label = "الحالة",
                            value = statusLabel(activeRequest.status)
                        )
                    }
                }

                // Cancel action (only while REQUESTED or PREPARING)
                if (activeRequest.status == PickupStatus.NOTIFIED || activeRequest.status == PickupStatus.IN_PREP) {
                    Button(
                        onClick = { showCancelDialog = true },
                        enabled = !isCancelling,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("إلغاء الطلب")
                    }
                }
            }
        }
    }

    if (showCancelDialog) {
        CancelRequestDialog(
            isLoading = isCancelling,
            onDismiss = {
                if (!isCancelling) showCancelDialog = false
            },
            onConfirm = { reason ->
                isCancelling = true
                scope.launch {
                    pickupRepository.cancelPickup(requestId, reason)
                        .onSuccess { cancelled ->
                            request = cancelled
                            showCancelDialog = false
                            isCancelling = false
                            onNavigateBack()
                        }
                        .onFailure { error ->
                            errorMessage = error.message ?: "فشل إلغاء الطلب"
                            isCancelling = false
                            showCancelDialog = false
                        }
                }
            }
        )
    }
}

private fun statusLabel(status: String): String {
    val step = trackingSteps.firstOrNull { it.first == status }
    if (step != null) return step.second
    return terminalStatusLabels[status] ?: status
}

@Composable
private fun StepperCard(
    request: PickupRequestDto,
    modifier: Modifier = Modifier
) {
    val currentStatusIndex = trackingSteps.indexOfFirst { it.first == request.status }
    val isTerminal = terminalStatusLabels.containsKey(request.status)
    val reachedIndex = when {
        isTerminal && request.status == PickupStatus.COMPLETED -> trackingSteps.lastIndex
        currentStatusIndex >= 0 -> currentStatusIndex
        else -> 0
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            trackingSteps.forEachIndexed { index, (_, label) ->
                val reached = index <= reachedIndex
                val isLast = index == trackingSteps.lastIndex
                val dotColor = if (reached) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(dotColor, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = label,
                        style = if (index == reachedIndex) {
                            MaterialTheme.typography.titleSmall
                        } else {
                            MaterialTheme.typography.bodyMedium
                        },
                        fontWeight = if (index == reachedIndex) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (reached) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                if (!isLast) {
                    Row {
                        Spacer(modifier = Modifier.width(5.dp))
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .height(18.dp)
                                .background(
                                    if (index < reachedIndex) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    }
                                )
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
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
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
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

@Composable
private fun CancelRequestDialog(
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (reason: String) -> Unit
) {
    var reason by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إلغاء الطلب") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("يرجى تحديد سبب إلغاء طلب الاستلام")
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("سبب الإلغاء") },
                    enabled = !isLoading,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(reason.trim()) },
                enabled = !isLoading && reason.isNotBlank()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("تأكيد الإلغاء")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) {
                Text("رجوع")
            }
        }
    )
}
