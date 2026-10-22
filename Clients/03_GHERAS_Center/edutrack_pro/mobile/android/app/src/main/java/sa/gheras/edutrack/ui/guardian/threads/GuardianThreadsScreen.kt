package sa.gheras.edutrack.ui.guardian.threads

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Class
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.Badge
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sa.gheras.edutrack.data.entity.ThreadEntity
import sa.gheras.edutrack.ui.common.EmptyView
import sa.gheras.edutrack.ui.common.LoadingView
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuardianThreadsScreen(
    viewModel: GuardianThreadsViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToThread: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showCreateDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("المحادثات والرسائل", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, contentDescription = "محادثة جديدة")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Student Filters Row if multiple students
            if (state.students.size > 1) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        FilterChip(
                            selected = state.selectedStudentId == null,
                            onClick = { viewModel.selectStudent(null) },
                            label = { Text("الكل") }
                        )
                    }
                    items(state.students, key = { it.id }) { student ->
                        FilterChip(
                            selected = state.selectedStudentId == student.id,
                            onClick = { viewModel.selectStudent(student.id) },
                            label = { Text(student.name) }
                        )
                    }
                }
            }

            // Content Area
            if (state.isLoading && state.threads.isEmpty()) {
                LoadingView()
            } else if (state.threads.isEmpty()) {
                EmptyView(
                    message = "لا توجد أي محادثات حالياً",
                    icon = Icons.Default.Chat
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(state.threads, key = { it.id }) { thread ->
                        val studentName = state.students.find { it.id == thread.studentId }?.name ?: ""
                        ThreadCard(
                            thread = thread,
                            studentName = studentName,
                            onClick = { onNavigateToThread(thread.id) }
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateThreadDialog(
            students = state.students,
            initialStudentId = state.selectedStudentId ?: state.students.firstOrNull()?.id.orEmpty(),
            isSubmitting = state.isCreatingThread,
            onDismiss = { showCreateDialog = false },
            onSubmit = { studentId, subject, message ->
                viewModel.createThread(studentId, subject, message) { newThreadId ->
                    showCreateDialog = false
                    onNavigateToThread(newThreadId)
                }
            }
        )
    }
}

@Composable
fun ThreadCard(
    thread: ThreadEntity,
    studentName: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val formatter = remember {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = thread.subject,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Text(
                    text = formatter.format(thread.lastMessageAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            if (studentName.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "الطالب/ـة: $studentName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            thread.lastMessagePreview?.let { preview ->
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Linked context badges
            if (thread.linkedAssignmentId != null || thread.linkedSummaryId != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (thread.linkedAssignmentId != null) {
                        SuggestionChip(
                            onClick = {},
                            label = { Text("مرتبط بواجب", style = MaterialTheme.typography.labelSmall) },
                            icon = { Icon(Icons.Default.MenuBook, contentDescription = null, modifier = Modifier.size(14.dp)) }
                        )
                    }
                    if (thread.linkedSummaryId != null) {
                        SuggestionChip(
                            onClick = {},
                            label = { Text("مرتبط بملخص درس", style = MaterialTheme.typography.labelSmall) },
                            icon = { Icon(Icons.Default.Class, contentDescription = null, modifier = Modifier.size(14.dp)) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateThreadDialog(
    students: List<sa.gheras.edutrack.data.entity.StudentEntity>,
    initialStudentId: String,
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (studentId: String, subject: String, firstMessage: String) -> Unit
) {
    var selectedStudentId by remember { mutableStateOf(initialStudentId) }
    var subject by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    BasicAlertDialog(onDismissRequest = { if (!isSubmitting) onDismiss() }) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = "بدء محادثة جديدة",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (students.size > 1) {
                    Text("اختر الطالب:", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(students, key = { it.id }) { s ->
                            FilterChip(
                                selected = selectedStudentId == s.id,
                                onClick = { selectedStudentId = s.id },
                                label = { Text(s.name) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                OutlinedTextField(
                    value = subject,
                    onValueChange = { subject = it },
                    label = { Text("موضوع المحادثة") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text("نص الرسالة الأولى") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss, enabled = !isSubmitting) {
                        Text("إلغاء")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (selectedStudentId.isNotBlank() && subject.isNotBlank() && message.isNotBlank()) {
                                onSubmit(selectedStudentId, subject.trim(), message.trim())
                            }
                        },
                        enabled = !isSubmitting && selectedStudentId.isNotBlank() && subject.isNotBlank() && message.isNotBlank()
                    ) {
                        if (isSubmitting) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text("إرسال")
                        }
                    }
                }
            }
        }
    }
}
