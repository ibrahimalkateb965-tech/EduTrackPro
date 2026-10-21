package sa.gheras.edutrack.ui.teacher.homework

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AssignmentTurnedIn
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import sa.gheras.edutrack.data.entity.AssignmentEntity
import sa.gheras.edutrack.data.entity.SubmissionEntity
import sa.gheras.edutrack.data.remote.dto.ReminderRecipientDto
import sa.gheras.edutrack.data.remote.dto.SubmissionDto
import sa.gheras.edutrack.data.remote.dto.TeacherAssignmentStatsDto
import sa.gheras.edutrack.data.repo.AssignmentsRepository
import sa.gheras.edutrack.data.repo.StudentsRepository
import sa.gheras.edutrack.data.repo.mappers.AssignmentMappers
import sa.gheras.edutrack.ui.common.EmptyView
import sa.gheras.edutrack.ui.common.LoadingView
import sa.gheras.edutrack.ui.common.Num
import sa.gheras.edutrack.ui.guardian.PendingNavy
import sa.gheras.edutrack.ui.guardian.PresentGreen
import sa.gheras.edutrack.ui.guardian.RevisionOrange
import sa.gheras.edutrack.ui.guardian.SubmittedAmber

enum class TeacherSubmissionFilter(val label: String) {
    ALL("الكل"),
    NEEDS_REVIEW("بانتظار المراجعة"),
    GRADED("تمت المراجعة"),
    PENDING("لم يرسلوا")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewSubmissionsScreen(
    assignmentId: String,
    assignmentsRepository: AssignmentsRepository,
    studentsRepository: StudentsRepository,
    onNavigateBack: () -> Unit,
    onGradeSubmission: (submissionId: String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var assignment by remember { mutableStateOf<AssignmentEntity?>(null) }
    var stats by remember { mutableStateOf(TeacherAssignmentStatsDto()) }
    var submissionItems by remember { mutableStateOf<List<SubmissionDto>>(emptyList()) }
    var selectedFilter by remember { mutableStateOf(TeacherSubmissionFilter.ALL) }
    var isLoading by remember { mutableStateOf(true) }

    var showReminderDialog by remember { mutableStateOf(false) }
    var reminderRecipients by remember { mutableStateOf<List<ReminderRecipientDto>>(emptyList()) }
    var isSendingReminder by remember { mutableStateOf(false) }

    fun refreshData() {
        scope.launch {
            isLoading = true
            assignment = assignmentsRepository.getById(assignmentId)
            val result = assignmentsRepository.getAssignmentSubmissions(assignmentId)
            if (result.isSuccess) {
                val data = result.getOrThrow()
                stats = data.stats ?: TeacherAssignmentStatsDto()
                submissionItems = data.items
            }
            isLoading = false
        }
    }

    LaunchedEffect(assignmentId) {
        refreshData()
    }

    if (showReminderDialog) {
        AlertDialog(
            onDismissRequest = { showReminderDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("إرسال تذكير للطلاب", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column {
                    Text(
                        "سيتم إرسال إشعار تذكيري لأولياء أمور الطلاب الذين لم يرسلوا الحل أو طُلب منهم إعادة الحل:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    if (reminderRecipients.isEmpty()) {
                        Text("جاري استرجاع قائمة المستلمين...", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(
                            "الطلاب المستهدفون (${Num.format(reminderRecipients.size)}):",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        LazyColumn(modifier = Modifier.height(140.dp)) {
                            items(reminderRecipients) { r ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(r.studentName, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                    Text(
                                        if (r.status == "revision_requested") "إعادة الحل" else "لم يرسل",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (r.status == "revision_requested") RevisionOrange else PendingNavy
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isSendingReminder = true
                        scope.launch {
                            val res = assignmentsRepository.sendReminders(assignmentId)
                            isSendingReminder = false
                            showReminderDialog = false
                            if (res.isSuccess) {
                                val sentCount = res.getOrThrow().sent
                                Toast.makeText(context, "تم إرسال التذكير بنجاح لـ ${Num.format(sentCount)} طالب", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "فترة التهدئة نشطة، يرجى الانتظار قبل التذكير مجدداً", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    enabled = !isSendingReminder
                ) {
                    if (isSendingReminder) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White)
                    } else {
                        Text("إرسال التذكير الآن")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showReminderDialog = false }) {
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
                        Text("تسليمات الواجب", fontWeight = FontWeight.Bold)
                        if (assignment != null) {
                            Text(
                                assignment!!.title,
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
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            val res = assignmentsRepository.previewReminders(assignmentId)
                            if (res.isSuccess) {
                                reminderRecipients = res.getOrThrow().students
                                showReminderDialog = true
                            } else {
                                Toast.makeText(context, "تعذر استرجاع المستلمين للتذكير", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) {
                        Icon(Icons.Default.Notifications, contentDescription = "تذكير")
                    }
                }
            )
        }
    ) { padding ->
        if (isLoading) {
            LoadingView(modifier = Modifier.fillMaxSize())
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // 3 Stats Cards
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Graded Card
                    StatBox(
                        title = "تمت المراجعة",
                        count = stats.graded,
                        color = PresentGreen,
                        bgColor = Color(0xFFE8F5E9),
                        modifier = Modifier.weight(1f)
                    )
                    // Awaiting Review Card
                    StatBox(
                        title = "بانتظار المراجعة",
                        count = stats.submitted + stats.revisionRequested,
                        color = SubmittedAmber,
                        bgColor = Color(0xFFFFF8E1),
                        modifier = Modifier.weight(1f)
                    )
                    // Pending Card
                    StatBox(
                        title = "لم يرسلوا",
                        count = stats.pending,
                        color = PendingNavy,
                        bgColor = Color(0xFFE3F2FD),
                        modifier = Modifier.weight(1f)
                    )
                }

                // Filter Row
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(TeacherSubmissionFilter.values()) { f ->
                        FilterChip(
                            selected = selectedFilter == f,
                            onClick = { selectedFilter = f },
                            label = { Text(f.label) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                val filteredList = submissionItems.filter { item ->
                    when (selectedFilter) {
                        TeacherSubmissionFilter.ALL -> true
                        TeacherSubmissionFilter.NEEDS_REVIEW -> item.status in listOf("submitted", "revision_requested")
                        TeacherSubmissionFilter.GRADED -> item.status == "graded"
                        TeacherSubmissionFilter.PENDING -> item.status == null || item.status == "pending"
                    }
                }

                if (filteredList.isEmpty()) {
                    EmptyView(
                        message = "لا توجد تسليمات في هذا التصنيف",
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(filteredList, key = { it.id }) { sub ->
                            TeacherSubmissionCard(
                                submission = sub,
                                onClick = {
                                    if (sub.status != null && sub.status != "pending") {
                                        onGradeSubmission(sub.id)
                                    } else {
                                        Toast.makeText(context, "الطالب لم يرسل حلاً بعد لتقييمه", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatBox(
    title: String,
    count: Int,
    color: Color,
    bgColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = bgColor,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = Num.format(count),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun TeacherSubmissionCard(
    submission: SubmissionDto,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
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
                    text = submission.studentName ?: "الطالب",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                if (submission.attemptNumber > 0) {
                    Text(
                        text = "المحاولة: ${Num.format(submission.attemptNumber)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Status chip
            val (badgeText, badgeBg, badgeFg) = when (submission.status) {
                "graded" -> Triple(
                    submission.gradeLevel?.let { AssignmentMappers.gradeLevelToBadgeArabic(it) } ?: "تمت المراجعة",
                    Color(0xFFE8F5E9),
                    PresentGreen
                )
                "revision_requested" -> Triple("إعادة الحل", Color(0xFFFBE9E7), RevisionOrange)
                "submitted" -> Triple("قيد المراجعة", Color(0xFFFFF8E1), SubmittedAmber)
                else -> Triple("لم يرسل", Color(0xFFE3F2FD), PendingNavy)
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = badgeBg
            ) {
                Text(
                    text = badgeText,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = badgeFg,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
