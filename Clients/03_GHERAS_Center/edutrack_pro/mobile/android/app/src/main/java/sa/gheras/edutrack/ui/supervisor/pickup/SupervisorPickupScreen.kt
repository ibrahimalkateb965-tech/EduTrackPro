package sa.gheras.edutrack.ui.supervisor.pickup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
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
import sa.gheras.edutrack.data.remote.dto.PickupRequestDto
import sa.gheras.edutrack.data.remote.dto.PickupStatus
import sa.gheras.edutrack.ui.theme.PresentGreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupervisorPickupScreen(
    viewModel: SupervisorGateViewModel,
    onNavigateToHandover: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

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
                    Text("بوابة تسليم الطلاب", fontWeight = FontWeight.Bold)
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (uiState.requests.isEmpty() && !uiState.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "لا توجد طلبات تسليم نشطة عند البوابة",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    item {
                        SupervisorGateSummaryRow(
                            readyCount = uiState.requests.count { it.status == PickupStatus.READY },
                            preparingCount = uiState.requests.count { it.status == PickupStatus.IN_PREP },
                            requestedCount = uiState.requests.count { it.status == PickupStatus.NOTIFIED }
                        )
                    }
                    items(uiState.requests, key = { it.id }) { request ->
                        SupervisorPickupCard(
                            request = request,
                            isActionLoading = uiState.isLoading,
                            onApproveEarlyLeaveClick = { viewModel.approveEarlyLeave(request.id) },
                            onHandoverClick = { onNavigateToHandover(request.id) }
                        )
                    }
                }
            }

            if (uiState.isLoading && uiState.requests.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun SupervisorGateSummaryRow(
    readyCount: Int,
    preparingCount: Int,
    requestedCount: Int,
    modifier: Modifier = Modifier
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        GateSummaryBadge(
            label = "جاهز: $readyCount",
            containerColor = PresentGreen,
            modifier = Modifier.weight(1f)
        )
        GateSummaryBadge(
            label = "قيد التجهيز: $preparingCount",
            containerColor = Color(0xFF2563EB),
            modifier = Modifier.weight(1f)
        )
        GateSummaryBadge(
            label = "بانتظار: $requestedCount",
            containerColor = Color(0xFFF59E0B),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun GateSummaryBadge(
    label: String,
    containerColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(containerColor.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = containerColor,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun SupervisorPickupCard(
    request: PickupRequestDto,
    isActionLoading: Boolean,
    onApproveEarlyLeaveClick: () -> Unit,
    onHandoverClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val badgeColor = when (request.status) {
        PickupStatus.IN_PREP -> Color(0xFF2563EB)
        PickupStatus.READY -> PresentGreen
        else -> Color(0xFFF59E0B)
    }
    val badgeLabel = when (request.status) {
        PickupStatus.IN_PREP -> "جاري تجهيزه من المعلمة"
        PickupStatus.READY -> "جاهز عند البوابة"
        else -> "بانتظار بدء التجهيز"
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = request.studentName ?: "-",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    request.roomName?.takeIf { it.isNotBlank() }?.let { room ->
                        Text(
                            text = room,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .background(badgeColor.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = badgeLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = badgeColor,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "المستلم",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = request.delegateName?.takeIf { it.isNotBlank() }
                        ?.let { "مفوض: $it" } ?: "ولي الأمر",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 16.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "وقت الطلب",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = request.pickupTime ?: "-",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 16.dp)
                )
            }

            if (request.isEarlyLeave) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFF59E0B).copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "استئذان مبكر",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFF59E0B),
                        fontWeight = FontWeight.SemiBold
                    )
                    if (!request.earlyLeaveApproved) {
                        Button(
                            onClick = onApproveEarlyLeaveClick,
                            enabled = !isActionLoading
                        ) {
                            if (isActionLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text("اعتماد الاستئذان")
                        }
                    }
                }
            }

            when (request.status) {
                PickupStatus.READY -> {
                    Button(
                        onClick = onHandoverClick,
                        enabled = !isActionLoading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("تسليم الطالب")
                    }
                }
            }
        }
    }
}
