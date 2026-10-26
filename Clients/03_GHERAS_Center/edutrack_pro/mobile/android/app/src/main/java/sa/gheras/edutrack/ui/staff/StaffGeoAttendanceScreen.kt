package sa.gheras.edutrack.ui.staff

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationCompat
import androidx.core.location.LocationManagerCompat
import sa.gheras.edutrack.data.entity.StaffAttendanceEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StaffGeoAttendanceScreen(
    viewModel: StaffGeoAttendanceViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val context = LocalContext.current
    val locationManager = remember {
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }
    val currentSignal = remember { mutableStateOf<CancellationSignal?>(null) }
    var locationProviderDisabled by remember { mutableStateOf(false) }

    fun readCurrentLocation() {
        val provider = when {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> {
                locationProviderDisabled = true
                return
            }
        }
        locationProviderDisabled = false
        currentSignal.value?.cancel()
        val signal = CancellationSignal()
        currentSignal.value = signal
        try {
            LocationManagerCompat.getCurrentLocation(
                locationManager,
                provider,
                signal,
                ContextCompat.getMainExecutor(context)
            ) { loc ->
                if (loc != null) {
                    viewModel.updateLocation(
                        loc.latitude,
                        loc.longitude,
                        loc.accuracy.toDouble(),
                        LocationCompat.isMock(loc)
                    )
                }
            }
        } catch (_: SecurityException) {
            viewModel.onLocationPermissionDenied()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.none { it }) {
            viewModel.onLocationPermissionDenied()
        } else {
            readCurrentLocation()
        }
    }

    LaunchedEffect(Unit) {
        val fineGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (fineGranted || coarseGranted) {
            readCurrentLocation()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose { currentSignal.value?.cancel() }
    }

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
                title = { Text("الحضور والانصراف للكادر") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshAll() }) {
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
            // 1. Shift & Status Header
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = state.activeShiftName ?: "الوردية الصباحية",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primary
                            ) {
                                Text(
                                    text = state.activeShiftTimes ?: "07:30 - 13:30",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val (dotColor, statusText) = when {
                                state.isPunchedOut -> Color(0xFF4CAF50) to "تم الانصراف"
                                state.isPunchedIn -> Color(0xFF2196F3) to "حاضر في المركز"
                                else -> Color(0xFFFF9800) to "لم يتم تسجيل الحضور"
                            }
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(dotColor)
                            )
                            Text(
                                text = "الحالة اليوم: $statusText",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        if (state.checkInTime != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            val timePart = state.checkInTime?.substringAfter("T")?.take(5) ?: state.checkInTime ?: ""
                            Text(
                                text = "وقت تسجيل الحضور: $timePart",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }

                        if (state.checkOutTime != null) {
                            val outPart = state.checkOutTime?.substringAfter("T")?.take(5) ?: state.checkOutTime ?: ""
                            Text(
                                text = "وقت تسجيل الانصراف: $outPart",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }

            // 2. Geofence & Location Validation Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = when {
                            state.isMockLocation || state.locationPermissionDenied -> Color(0xFFFFEBEE)
                            !state.hasLocationFix -> MaterialTheme.colorScheme.surfaceVariant
                            state.isInsideGeofence -> Color(0xFFE8F5E9)
                            else -> Color(0xFFFFEBEE)
                        }
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        when {
                            state.isMockLocation -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = Color(0xFFC62828)
                                    )
                                    Text(
                                        text = "تم رصد موقع وهمي",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFC62828)
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "يرجى إيقاف تطبيقات تزييف الموقع ثم الضغط على «تحديث موقعي».",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFC62828)
                                )
                            }
                            state.locationPermissionDenied -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = Color(0xFFC62828)
                                    )
                                    Text(
                                        text = "يجب السماح بالوصول إلى الموقع لتسجيل الحضور",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFC62828)
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "يرجى منح إذن الموقع للتطبيق ثم الضغط على «تحديث موقعي».",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFC62828)
                                )
                            }
                            !state.hasLocationFix -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MyLocation,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "جاري تحديد موقعك…",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "يرجى الانتظار حتى يظهر موقعك الحالي.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            else -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = if (state.isInsideGeofence) Icons.Default.CheckCircle else Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = if (state.isInsideGeofence) Color(0xFF2E7D32) else Color(0xFFC62828)
                                    )
                                    Text(
                                        text = if (state.isInsideGeofence) "داخل نطاق مركز غراس" else "خارج النطاق الجغرافي للمركز",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (state.isInsideGeofence) Color(0xFF2E7D32) else Color(0xFFC62828)
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Text(
                                    text = "المسافة من المركز: ${state.distanceMeters.toInt()}م (النطاق المسموح: ${state.geofenceRadiusMeters.toInt()}م)",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = "دقة إشارة GPS: ${state.currentAccuracy.toInt()}م (الحد المقبول: ${state.maxAccuracyMeters.toInt()}م)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (state.isAccuracyAcceptable) Color(0xFF2E7D32) else Color(0xFFC62828)
                                )
                            }
                        }

                        if (locationProviderDisabled) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "خدمة الموقع مغلقة. يرجى تشغيل GPS/الموقع من إعدادات الجهاز ثم الضغط على «تحديث موقعي».",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFC62828)
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedButton(
                            onClick = { readCurrentLocation() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                Icons.Default.MyLocation,
                                contentDescription = "تحديث موقعي",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("تحديث موقعي", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            // 3. Punch Actions
            item {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (!state.isPunchedIn) {
                        Button(
                            onClick = { viewModel.punchCheckIn() },
                            enabled = !state.isActionInProgress && state.hasLocationFix && !state.isMockLocation,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                        ) {
                            if (state.isActionInProgress) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                            } else {
                                Icon(Icons.Default.CheckCircle, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("تسجيل الحضور الآن", style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    } else if (!state.isPunchedOut) {
                        Button(
                            onClick = { viewModel.punchCheckOut() },
                            enabled = !state.isActionInProgress && state.hasLocationFix && !state.isMockLocation,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                        ) {
                            if (state.isActionInProgress) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                            } else {
                                Icon(Icons.Default.CheckCircle, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("تسجيل الانصراف الآن", style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    } else {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFFE8F5E9)
                        ) {
                            Text(
                                text = "اكتمل تسجيل الحضور والانصراف لهذا اليوم بنجاح",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF2E7D32),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }
            }

            // 4. Monthly Statistics
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "ملخص شهر ${state.historyMonth}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            StatBox(label = "أيام الحضور", value = "${state.presentDays}", color = Color(0xFF2E7D32))
                            StatBox(label = "التأخيرات", value = "${state.lateDays}", color = Color(0xFFFF9800))
                            StatBox(label = "إجمالي الساعات", value = "${state.totalWorkHours}س", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            // 5. History Section Header
            item {
                Text(
                    text = "سجل الحضور لهذا الشهر",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            // 6. History List
            if (state.historyRecords.isEmpty()) {
                item {
                    Text(
                        text = "لا توجد سجلات حضور مسجلة لهذا الشهر حتى الآن",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp)
                    )
                }
            } else {
                items(state.historyRecords) { rec ->
                    StaffAttendanceRow(record = rec)
                }
            }
        }
    }
}

@Composable
private fun StatBox(label: String, value: String, color: Color) {
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
private fun StaffAttendanceRow(record: StaffAttendanceEntity) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = record.date,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                val inTime = record.checkInTime?.substringAfter("T")?.take(5) ?: "--:--"
                val outTime = record.checkOutTime?.substringAfter("T")?.take(5) ?: "--:--"
                Text(
                    text = "دخول: $inTime | خروج: $outTime",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
    }
}
