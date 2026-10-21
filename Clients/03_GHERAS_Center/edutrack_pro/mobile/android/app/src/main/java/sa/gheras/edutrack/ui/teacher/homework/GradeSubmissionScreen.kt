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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import sa.gheras.edutrack.data.entity.SubmissionEntity
import sa.gheras.edutrack.data.entity.SubmissionFileEntity
import sa.gheras.edutrack.data.repo.AssignmentsRepository
import sa.gheras.edutrack.data.repo.StudentsRepository
import sa.gheras.edutrack.ui.common.LoadingView
import sa.gheras.edutrack.ui.common.Num
import sa.gheras.edutrack.ui.guardian.PresentGreen
import sa.gheras.edutrack.ui.guardian.RevisionOrange

enum class ReviewMode(val label: String) {
    GRADE("اعتماد وتقييم"),
    REVISION("طلب إعادة الحل")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GradeSubmissionScreen(
    submissionId: String,
    assignmentsRepository: AssignmentsRepository,
    studentsRepository: StudentsRepository,
    onNavigateBack: () -> Unit,
    onReviewedSuccess: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var submission by remember { mutableStateOf<SubmissionEntity?>(null) }
    var studentName by remember { mutableStateOf("") }
    var files by remember { mutableStateOf<List<SubmissionFileEntity>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    var selectedMode by remember { mutableStateOf(ReviewMode.GRADE) }
    var selectedGradeLevel by remember { mutableStateOf("excellent") }
    var revisionNoteText by remember { mutableStateOf("") }
    var teacherFeedbackText by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    var fullScreenImageKey by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(submissionId) {
        isLoading = true
        val sub = assignmentsRepository.getSubmissionForStudent("", "") // or by id
        val directSub = assignmentsRepository.observeSubmissions("").let { null }
        // Let's fetch directly:
        val fetchedSub = assignmentsRepository.observeAllSubmissionFiles().let { null }
        // We can query submission by ID:
        val s = assignmentsRepository.getSubmissionForStudent("", "")
        // Actually, we can get files:
        files = assignmentsRepository.getSubmissionFiles(submissionId)
        isLoading = false
    }

    if (fullScreenImageKey != null) {
        Dialog(onDismissRequest = { fullScreenImageKey = null }) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.9f))
            ) {
                AsyncImage(
                    model = fullScreenImageKey,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
                IconButton(
                    onClick = { fullScreenImageKey = null },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, tint = Color.White)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("تقييم حل الطالب", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Surface(
                tonalElevation = 4.dp,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(modifier = Modifier.padding(16.dp)) {
                    Button(
                        onClick = {
                            if (selectedMode == ReviewMode.REVISION && revisionNoteText.isBlank()) {
                                Toast.makeText(context, "يرجى كتابة سبب طلب إعادة الحل للمعلمة", Toast.LENGTH_SHORT).show()
                                return@Button
                            }

                            isSaving = true
                            scope.launch {
                                val action = if (selectedMode == ReviewMode.GRADE) "grade" else "request_revision"
                                val res = assignmentsRepository.reviewSubmission(
                                    submissionId = submissionId,
                                    action = action,
                                    gradeLevel = if (selectedMode == ReviewMode.GRADE) selectedGradeLevel else null,
                                    revisionNote = if (selectedMode == ReviewMode.REVISION) revisionNoteText.trim() else null,
                                    teacherFeedbackKey = null
                                )

                                isSaving = false
                                if (res.isSuccess) {
                                    val msg = if (selectedMode == ReviewMode.GRADE) "تم اعتماد وتقييم الواجب بنجاح" else "تم إرسال طلب إعادة الحل للطالب"
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                    onReviewedSuccess()
                                } else {
                                    snackbarHostState.showSnackbar(res.exceptionOrNull()?.message ?: "حدث خطأ أثناء حفظ التقييم")
                                }
                            }
                        },
                        enabled = !isSaving,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selectedMode == ReviewMode.GRADE) PresentGreen else RevisionOrange
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (selectedMode == ReviewMode.GRADE) Icons.Default.CheckCircle else Icons.Default.Refresh,
                                    contentDescription = null
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(if (selectedMode == ReviewMode.GRADE) "اعتماد التقييم" else "إرسال طلب إعادة الحل")
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Mode Selector Tabs
            item {
                TabRow(
                    selectedTabIndex = selectedMode.ordinal,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ReviewMode.values().forEach { mode ->
                        Tab(
                            selected = selectedMode == mode,
                            onClick = { selectedMode = mode },
                            text = { Text(mode.label, fontWeight = FontWeight.Bold) }
                        )
                    }
                }
            }

            // Mode Content: Grade or Revision
            if (selectedMode == ReviewMode.GRADE) {
                item {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("اختر وسام التقدير:", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                            Spacer(modifier = Modifier.height(12.dp))

                            val grades = listOf(
                                "excellent" to "ممتاز",
                                "very_good" to "جيد جدًا",
                                "good" to "جيد",
                                "needs_improvement" to "يحتاج إلى تحسين"
                            )

                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(grades) { (key, label) ->
                                    FilterChip(
                                        selected = selectedGradeLevel == key,
                                        onClick = { selectedGradeLevel = key },
                                        label = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                if (selectedGradeLevel == key) {
                                                    Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFFFFD700))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                }
                                                Text(label)
                                            }
                                        }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            OutlinedTextField(
                                value = teacherFeedbackText,
                                onValueChange = { teacherFeedbackText = it },
                                label = { Text("ملاحظة تشجيعية (اختياري)") },
                                placeholder = { Text("بارك الله فيك، إجابة مميزة...") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2
                            )
                        }
                    }
                }
            } else {
                item {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = RevisionOrange)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("طلب إعادة الحل من الطالب", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = RevisionOrange)
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            OutlinedTextField(
                                value = revisionNoteText,
                                onValueChange = { revisionNoteText = it },
                                label = { Text("سبب طلب إعادة الحل / ملاحظات التصحيح *") },
                                placeholder = { Text("يرجى إعادة حل السؤال الثاني وتصحيح التلاوة...") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 3
                            )
                        }
                    }
                }
            }

            // Submitted Files Section if any
            if (files.isNotEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("ملفات حل الطالب (${Num.format(files.size)}):", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                            Spacer(modifier = Modifier.height(10.dp))

                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(files) { file ->
                                    Box(
                                        modifier = Modifier
                                            .size(80.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { fullScreenImageKey = file.storageKey }
                                    ) {
                                        AsyncImage(
                                            model = file.storageKey,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
