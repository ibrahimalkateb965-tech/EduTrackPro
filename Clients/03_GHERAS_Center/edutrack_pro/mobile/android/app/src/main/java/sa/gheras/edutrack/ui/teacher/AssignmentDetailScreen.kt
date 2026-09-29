package sa.gheras.edutrack.ui.teacher

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.lifecycle.compose.LifecycleResumeEffect
import sa.gheras.edutrack.ui.teacher.media.AssignmentMediaParser
import sa.gheras.edutrack.ui.teacher.media.AudioPlayerHelper
import sa.gheras.edutrack.ui.teacher.media.MediaAttachment
import sa.gheras.edutrack.ui.teacher.media.ShareHelper
import sa.gheras.edutrack.ui.common.LoadingView
import sa.gheras.edutrack.ui.common.Num
import sa.gheras.edutrack.ui.theme.PresentGreen
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import coil.request.ImageRequest
import sa.gheras.edutrack.BuildConfig
import sa.gheras.edutrack.data.entity.SubmissionFileEntity

data class FullScreenViewerTarget(
    val studentName: String,
    val fileUrls: List<String>,
    val initialIndex: Int = 0,
    val submissionStatus: StudentSubmissionStatus? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssignmentDetailScreen(
    viewModel: AssignmentDetailViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var viewingTarget by remember { mutableStateOf<FullScreenViewerTarget?>(null) }
    var gradingTarget by remember { mutableStateOf<StudentSubmissionStatus?>(null) }

    // Pull fresh submissions and files on entry and when resuming
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.assignment?.title ?: "تفاصيل الواجب", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                    }
                },
                actions = {
                    state.assignment?.let { assign ->
                        IconButton(
                            onClick = {
                                ShareHelper.shareAssignmentViaWhatsApp(
                                    context = context,
                                    assignment = assign,
                                    roomName = state.roomName
                                )
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "مشاركة عبر واتساب",
                                tint = Color(0xFF25D366)
                            )
                        }
                    }
                    if (state.isRefreshing) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .size(24.dp)
                        )
                    } else {
                        IconButton(onClick = viewModel::refresh) {
                            Icon(Icons.Default.Refresh, contentDescription = "تحديث")
                        }
                    }
                }
            )
        },
        modifier = modifier
    ) { innerPadding ->
        if (state.isLoading) {
            LoadingView(modifier = Modifier.padding(innerPadding))
        } else {
            val assign = state.assignment

            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // Info Card
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = assign?.title ?: "",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "${state.roomName} • ${assign?.subject ?: "عام"}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )

                            if (assign?.dueDate != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Event,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.outline
                                    )
                                    Spacer(modifier = Modifier.size(4.dp))
                                    Text(
                                        text = "تاريخ التسليم: ${assign.dueDate}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }

                            if (!assign?.pageRef.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Book,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.secondary
                                    )
                                    Spacer(modifier = Modifier.size(4.dp))
                                    Text(
                                        text = "مرجع الصفحة: ${assign.pageRef}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                            }

                            val (cleanInstructions, mediaAttachments) = remember(assign?.instructions) {
                                AssignmentMediaParser.parse(assign?.instructions)
                            }

                            if (cleanInstructions.isNotBlank()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            text = "التعليمات والتوجيهات:",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = cleanInstructions,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }

                            if (mediaAttachments.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "المرفقات والوسائط التعليمية (${Num.formatInt(mediaAttachments.size)}):",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    mediaAttachments.forEach { media ->
                                        MediaAttachmentItemCard(
                                            media = media,
                                            onMediaClick = { title, uri ->
                                                viewingTarget = FullScreenViewerTarget(
                                                    studentName = title,
                                                    fileUrls = listOf(uri)
                                                )
                                            }
                                        )
                                    }
                                }
                            }

                            // Progress Summary
                            if (state.totalCount > 0) {
                                Spacer(modifier = Modifier.height(14.dp))
                                val progress = (state.submittedCount.toFloat() / state.totalCount.toFloat()).coerceIn(0f, 1f)
                                val percent = (progress * 100).toInt()

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "نسبة الإنجاز: ${Num.formatInt(state.submittedCount)} من ${Num.formatInt(state.totalCount)} طالب",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "${Num.formatInt(percent)}%",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (percent == 100) PresentGreen else MaterialTheme.colorScheme.primary
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                    color = if (percent == 100) PresentGreen else MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            }
                        }
                    }
                }

                // Submissions Header & Filters
                item {
                    Column {
                        Text(
                            text = "تسليمات الطلاب",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(SubmissionFilter.entries) { filter ->
                                val count = when (filter) {
                                    SubmissionFilter.ALL -> state.totalCount
                                    SubmissionFilter.SUBMITTED -> state.submittedCount
                                    SubmissionFilter.PENDING -> state.pendingCount
                                }
                                FilterChip(
                                    selected = state.filter == filter,
                                    onClick = { viewModel.setFilter(filter) },
                                    label = { Text("${filter.title} (${Num.formatInt(count)})") }
                                )
                            }
                        }
                    }
                }

                // Student Cards
                items(state.studentSubmissions, key = { it.student.id }) { item ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = item.student.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )

                                if (item.isSubmitted) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = PresentGreen.copy(alpha = 0.15f)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.CheckCircle,
                                                contentDescription = null,
                                                tint = PresentGreen,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.size(4.dp))
                                            Text(
                                                text = "مُسلَّم",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = PresentGreen,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                } else {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.HourglassEmpty,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.outline,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.size(4.dp))
                                            Text(
                                                text = "لم يُسلَّم بعد",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.outline
                                            )
                                        }
                                    }
                                }
                            }

                            // Submission Details if available
                            if (item.submission != null) {
                                // Homework Submission Photos
                                if (item.files.isNotEmpty()) {
                                    val allUrls = remember(item.files) { item.files.map { resolveImageUrl(it.storageKey) } }
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "صور وملفات الحل (${Num.formatInt(item.files.size)}):",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = "عرض بكامل الشاشة ↗",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .clickable {
                                                    viewingTarget = FullScreenViewerTarget(
                                                        studentName = item.student.name,
                                                        fileUrls = allUrls,
                                                        initialIndex = 0,
                                                        submissionStatus = item
                                                    )
                                                }
                                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        itemsIndexed(item.files, key = { _, file -> file.id }) { idx, file ->
                                            val resolvedUrl = allUrls.getOrElse(idx) { resolveImageUrl(file.storageKey) }
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                                modifier = Modifier
                                                    .size(64.dp)
                                                    .clickable {
                                                        viewingTarget = FullScreenViewerTarget(
                                                            studentName = item.student.name,
                                                            fileUrls = allUrls,
                                                            initialIndex = idx,
                                                            submissionStatus = item
                                                        )
                                                    }
                                            ) {
                                                AsyncImage(
                                                    model = ImageRequest.Builder(context)
                                                        .data(resolvedUrl)
                                                        .crossfade(true)
                                                        .build(),
                                                    contentDescription = "صورة الواجب",
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            }
                                        }
                                    }
                                }

                                if (item.submission.grade != null || !item.submission.feedback.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (item.submission.grade != null) {
                                            Text(
                                                text = "الدرجة: ${Num.formatDecimal(item.submission.grade)}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        if (!item.submission.feedback.isNullOrBlank()) {
                                            Spacer(modifier = Modifier.size(12.dp))
                                            Text(
                                                text = "ملاحظة: ${item.submission.feedback}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))
                                OutlinedButton(
                                    onClick = { gradingTarget = item },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(if (item.submission.grade != null) "تعديل تقييم الواجب" else "تقييم ورصد درجة الواجب")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Teacher Grading Dialog
    gradingTarget?.let { target ->
        target.submission?.let { sub ->
            TeacherGradingDialog(
                studentName = target.student.name,
                currentGrade = sub.grade,
                currentFeedback = sub.feedback,
                isGrading = state.isGrading,
                onDismiss = { if (!state.isGrading) gradingTarget = null },
                onConfirm = { grade, feedback ->
                    viewModel.gradeSubmission(sub.id, grade, feedback)
                    gradingTarget = null
                }
            )
        }
    }

    // Full-screen Media Viewer Dialog
    viewingTarget?.let { target ->
        FullScreenMediaViewerDialog(
            target = target,
            submissions = state.studentSubmissions,
            onDismiss = { viewingTarget = null },
            onGradeClick = { targetStatus ->
                gradingTarget = targetStatus
            }
        )
    }
}

@Composable
private fun TeacherGradingDialog(
    studentName: String,
    currentGrade: Double?,
    currentFeedback: String?,
    isGrading: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Double, String?) -> Unit
) {
    var gradeText by remember { mutableStateOf(currentGrade?.let { Num.formatDecimal(it) } ?: "") }
    var feedbackText by remember { mutableStateOf(currentFeedback ?: "") }
    var validationError by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "تقييم واجب الطالب: $studentName",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = gradeText,
                    onValueChange = {
                        gradeText = it
                        validationError = null
                    },
                    label = { Text("الدرجة (مثال: 10 أو 9.5)") },
                    isError = validationError != null,
                    supportingText = validationError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                    enabled = !isGrading,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = feedbackText,
                    onValueChange = { feedbackText = it },
                    label = { Text("ملاحظات المعلم والتعليق (اختياري)") },
                    placeholder = { Text("أحسنت عملاً، أو يرجى مراجعة المسألة الثانية...") },
                    enabled = !isGrading,
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                    shape = RoundedCornerShape(8.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        enabled = !isGrading,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("إلغاء")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val gradeVal = gradeText.toDoubleOrNull()
                            if (gradeVal == null || gradeVal < 0) {
                                validationError = "يرجى إدخال درجة رقمية صحيحة"
                            } else {
                                onConfirm(gradeVal, feedbackText.ifBlank { null })
                            }
                        },
                        enabled = !isGrading && gradeText.isNotBlank(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        if (isGrading) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp),
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text("حفظ التقييم")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FullScreenMediaViewerDialog(
    target: FullScreenViewerTarget,
    submissions: List<StudentSubmissionStatus> = emptyList(),
    onDismiss: () -> Unit,
    onGradeClick: ((StudentSubmissionStatus) -> Unit)? = null
) {
    val context = LocalContext.current
    var currentIndex by remember {
        mutableIntStateOf(target.initialIndex.coerceIn(0, (target.fileUrls.size - 1).coerceAtLeast(0)))
    }
    val currentUrl = target.fileUrls.getOrNull(currentIndex) ?: ""

    // Pinch-to-zoom and pan state
    var scale by remember(currentIndex) { mutableFloatStateOf(1f) }
    var offset by remember(currentIndex) { mutableStateOf(Offset.Zero) }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(1f, 5f)
        scale = newScale
        offset = if (newScale > 1f) {
            Offset(
                x = offset.x + panChange.x,
                y = offset.y + panChange.y
            )
        } else {
            Offset.Zero
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF0F172A)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Interactive media view
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(currentIndex) {
                            detectTapGestures(
                                onDoubleTap = {
                                    if (scale > 1f) {
                                        scale = 1f
                                        offset = Offset.Zero
                                    } else {
                                        scale = 2.5f
                                    }
                                }
                            )
                        }
                        .transformable(state = transformState),
                    contentAlignment = Alignment.Center
                ) {
                    if (currentUrl.isNotBlank()) {
                        val cleanMediaUrl = currentUrl.substringBefore('?')
                        val isImage = cleanMediaUrl.endsWith(".png", ignoreCase = true) ||
                                cleanMediaUrl.endsWith(".jpg", ignoreCase = true) ||
                                cleanMediaUrl.endsWith(".jpeg", ignoreCase = true) ||
                                cleanMediaUrl.endsWith(".webp", ignoreCase = true) ||
                                cleanMediaUrl.endsWith(".gif", ignoreCase = true)
                        val isPdf = cleanMediaUrl.endsWith(".pdf", ignoreCase = true)
                        val isAudio = cleanMediaUrl.endsWith(".mp3", ignoreCase = true) ||
                                cleanMediaUrl.endsWith(".m4a", ignoreCase = true) ||
                                cleanMediaUrl.endsWith(".aac", ignoreCase = true) ||
                                cleanMediaUrl.endsWith(".wav", ignoreCase = true) ||
                                cleanMediaUrl.endsWith(".ogg", ignoreCase = true)
                        val isDoc = cleanMediaUrl.endsWith(".doc", ignoreCase = true) || cleanMediaUrl.endsWith(".docx", ignoreCase = true)

                        if (!isImage) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(24.dp)
                            ) {
                                Icon(
                                    imageVector = when {
                                        isPdf -> Icons.Default.Description
                                        isAudio -> Icons.Default.Mic
                                        else -> Icons.Default.Description
                                    },
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(72.dp)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = when {
                                        isPdf -> "ملف مستند PDF"
                                        isAudio -> "تسجيل صوتي للحل"
                                        isDoc -> "ملف مستند Word"
                                        else -> "ملف مرفق"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "يمكنك فتح الملف أو تنزيله للمعاينة",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.LightGray
                                )
                                Spacer(modifier = Modifier.height(20.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Button(
                                        onClick = { ShareHelper.openAttachment(context, currentUrl) },
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("فتح الملف ↗")
                                    }
                                    OutlinedButton(
                                        onClick = { downloadMediaFile(context, currentUrl, target.studentName) },
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("تنزيل")
                                    }
                                }
                            }
                        } else {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(currentUrl)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = "معاينة الواجب بكامل الشاشة",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                        translationX = offset.x
                                        translationY = offset.y
                                    }
                            )
                        }
                    }
                }

                // Top Controls Bar
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                    color = Color.Black.copy(alpha = 0.75f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onDismiss) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "إغلاق",
                                    tint = Color.White
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = target.studentName.ifBlank { "معاينة الواجب" },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                if (target.fileUrls.size > 1) {
                                    Text(
                                        text = "الملف ${Num.formatInt(currentIndex + 1)} من ${Num.formatInt(target.fileUrls.size)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.LightGray
                                    )
                                }
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Download button
                            IconButton(
                                onClick = {
                                    downloadMediaFile(context, currentUrl, target.studentName)
                                },
                                enabled = currentUrl.isNotBlank()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = "تنزيل الملف",
                                    tint = if (currentUrl.isNotBlank()) MaterialTheme.colorScheme.primary else Color.Gray
                                )
                            }

                            // Share / Open external button
                            IconButton(
                                onClick = {
                                    ShareHelper.openAttachment(context, currentUrl)
                                },
                                enabled = currentUrl.isNotBlank()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = "مشاركة / فتح خارجي",
                                    tint = if (currentUrl.isNotBlank()) Color.White else Color.Gray
                                )
                            }
                        }
                    }
                }

                // Navigation Arrows if multiple files
                if (target.fileUrls.size > 1) {
                    if (currentIndex > 0) {
                        Surface(
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.6f),
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .padding(16.dp)
                                .size(48.dp)
                        ) {
                            IconButton(onClick = { currentIndex-- }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "السابق",
                                    tint = Color.White
                                )
                            }
                        }
                    }

                    if (currentIndex < target.fileUrls.size - 1) {
                        Surface(
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.6f),
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(16.dp)
                                .size(48.dp)
                        ) {
                            IconButton(onClick = { currentIndex++ }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = "التالي",
                                    tint = Color.White
                                )
                            }
                        }
                    }
                }

                // Bottom Action Bar: Quick Grade Button
                if (target.submissionStatus != null && onGradeClick != null) {
                    val liveStatus = submissions.firstOrNull { it.student.id == target.submissionStatus.student.id } ?: target.submissionStatus
                    val currentGrade = liveStatus.submission?.grade

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter),
                        color = Color.Black.copy(alpha = 0.8f)
                    ) {
                        Row(
                            modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                if (currentGrade != null) {
                                    Text(
                                        text = "الدرجة الحالية: ${Num.formatDecimal(currentGrade)}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = PresentGreen
                                    )
                                } else {
                                    Text(
                                        text = "الواجب بانتظار التقييم",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.LightGray
                                    )
                                }
                            }

                            Button(
                                onClick = { onGradeClick(liveStatus) },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    if (currentGrade != null) "تعديل الدرجة" else "تقييم الواجب",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun downloadMediaFile(context: Context, fileUrl: String, studentName: String = "") {
    try {
        val uri = Uri.parse(fileUrl)
        val cleanUrl = fileUrl.substringBefore('?')
        val ext = when {
            cleanUrl.endsWith(".png", ignoreCase = true) -> ".png"
            cleanUrl.endsWith(".jpg", ignoreCase = true) || cleanUrl.endsWith(".jpeg", ignoreCase = true) -> ".jpg"
            cleanUrl.endsWith(".webp", ignoreCase = true) -> ".webp"
            cleanUrl.endsWith(".gif", ignoreCase = true) -> ".gif"
            cleanUrl.endsWith(".mp4", ignoreCase = true) -> ".mp4"
            cleanUrl.endsWith(".mp3", ignoreCase = true) -> ".mp3"
            cleanUrl.endsWith(".m4a", ignoreCase = true) -> ".m4a"
            cleanUrl.endsWith(".aac", ignoreCase = true) -> ".aac"
            cleanUrl.endsWith(".wav", ignoreCase = true) -> ".wav"
            cleanUrl.endsWith(".ogg", ignoreCase = true) -> ".ogg"
            cleanUrl.endsWith(".pdf", ignoreCase = true) -> ".pdf"
            cleanUrl.endsWith(".docx", ignoreCase = true) -> ".docx"
            cleanUrl.endsWith(".doc", ignoreCase = true) -> ".doc"
            cleanUrl.endsWith(".xlsx", ignoreCase = true) -> ".xlsx"
            cleanUrl.endsWith(".xls", ignoreCase = true) -> ".xls"
            cleanUrl.endsWith(".zip", ignoreCase = true) -> ".zip"
            else -> ".jpg"
        }
        val safeName = if (studentName.isNotBlank()) {
            studentName.trim().replace(Regex("[^a-zA-Z0-9_\\u0600-\\u06FF]"), "_")
        } else "homework"
        val fileName = "${safeName}_${System.currentTimeMillis()}$ext"

        val request = DownloadManager.Request(uri)
            .setTitle(if (studentName.isNotBlank()) "واجب $studentName" else "ملف الواجب")
            .setDescription("جاري تنزيل ملف الواجب...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        if (dm != null) {
            dm.enqueue(request)
            Toast.makeText(context, "بدأ تنزيل الملف إلى مجلد التنزيلات: $fileName", Toast.LENGTH_SHORT).show()
        } else {
            ShareHelper.openAttachment(context, fileUrl)
        }
    } catch (_: Exception) {
        try {
            ShareHelper.openAttachment(context, fileUrl)
            Toast.makeText(context, "جاري فتح الملف عبر التطبيق الخارجي...", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(context, "تعذر تنزيل الملف", Toast.LENGTH_SHORT).show()
        }
    }
}

private fun resolveImageUrl(storageKeyOrUrl: String?): String {
    if (storageKeyOrUrl.isNullOrBlank()) return ""
    if (storageKeyOrUrl.startsWith("http://") || storageKeyOrUrl.startsWith("https://")) {
        return storageKeyOrUrl
    }
    val baseDomain = BuildConfig.BASE_URL.substringBefore("/api/v1")
    return if (storageKeyOrUrl.startsWith("/")) {
        "$baseDomain$storageKeyOrUrl"
    } else {
        "$baseDomain/api/v1/static/uploads/$storageKeyOrUrl"
    }
}

@Composable
fun MediaAttachmentItemCard(
    media: MediaAttachment,
    modifier: Modifier = Modifier,
    onMediaClick: ((title: String, uri: String) -> Unit)? = null
) {
    val context = LocalContext.current

    when (media.kind.lowercase()) {
        "voice_note" -> {
            AudioAttachmentPlayerCard(media = media, modifier = modifier)
        }
        "google_form" -> {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                modifier = modifier
                    .fillMaxWidth()
                    .clickable { ShareHelper.openAttachment(context, media.uri) }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.AddLink,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Column {
                            Text(
                                text = media.title.ifBlank { "نموذج أسئلة تفاعلي" },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "اضغط للإجابة عن الأسئلة عبر Google Form",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = { ShareHelper.openAttachment(context, media.uri) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("فتح ↗", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        "video_link" -> {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = modifier
                    .fillMaxWidth()
                    .clickable { ShareHelper.openAttachment(context, media.uri) }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Videocam,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Column {
                            Text(
                                text = media.title.ifBlank { "مقطع فيديو للشرح" },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "اضغط لمشاهدة شرح التكليف أو تنزيله",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { downloadMediaFile(context, media.uri, media.title) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "تنزيل",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        OutlinedButton(
                            onClick = { ShareHelper.openAttachment(context, media.uri) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text("مشاهدة ↗", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
        "pdf_document" -> {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = modifier
                    .fillMaxWidth()
                    .clickable { ShareHelper.openAttachment(context, media.uri) }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Description,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Column {
                            Text(
                                text = media.title.ifBlank { "مستند ورقة العمل (PDF)" },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "اضغط لتحميل أو فتح المستند",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { downloadMediaFile(context, media.uri, media.title) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "تنزيل",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        OutlinedButton(
                            onClick = { ShareHelper.openAttachment(context, media.uri) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text("عرض ↗", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
        "image" -> {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = modifier
                    .fillMaxWidth()
                    .clickable {
                        if (onMediaClick != null) {
                            onMediaClick(media.title.ifBlank { "صورة التكليف" }, media.uri)
                        } else {
                            ShareHelper.openAttachment(context, media.uri)
                        }
                    }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Image,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Column {
                            Text(
                                text = media.title.ifBlank { "صورة التكليف" },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "اضغط لعرض الصورة بحجم كامل أو تنزيلها",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { downloadMediaFile(context, media.uri, media.title) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "تنزيل",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                if (onMediaClick != null) {
                                    onMediaClick(media.title.ifBlank { "صورة التكليف" }, media.uri)
                                } else {
                                    ShareHelper.openAttachment(context, media.uri)
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text("معاينة ↗", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
        else -> {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = modifier
                    .fillMaxWidth()
                    .clickable { ShareHelper.openAttachment(context, media.uri) }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Default.AddLink,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = media.title.ifBlank { media.uri },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    OutlinedButton(
                        onClick = { ShareHelper.openLink(context, media.uri) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("فتح ↗", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
fun AudioAttachmentPlayerCard(
    media: MediaAttachment,
    modifier: Modifier = Modifier
) {
    val playerHelper = remember { AudioPlayerHelper() }
    var isPlaying by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            playerHelper.release()
        }
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(40.dp)
                ) {
                    IconButton(
                        onClick = {
                            if (isPlaying) {
                                playerHelper.pause()
                                isPlaying = false
                            } else {
                                playerHelper.play(
                                    uri = media.uri,
                                    onCompletion = { isPlaying = false }
                                )
                                isPlaying = true
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "إيقاف مؤقت" else "تشغيل",
                            tint = MaterialTheme.colorScheme.onSecondary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Column {
                    Text(
                        text = media.title.ifBlank { "تسجيل صوتي للتسميع والتوجيه" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    val durText = if (media.durationSeconds != null) {
                        "المدة: ${Num.formatInt(media.durationSeconds)} ثانية"
                    } else {
                        "مقطع صوتي بصيغة AAC"
                    }
                    Text(
                        text = durText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (isPlaying) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        Icons.Default.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    )
                    Text(
                        text = if (isPlaying) "جارِ التشغيل..." else "جاهز",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
