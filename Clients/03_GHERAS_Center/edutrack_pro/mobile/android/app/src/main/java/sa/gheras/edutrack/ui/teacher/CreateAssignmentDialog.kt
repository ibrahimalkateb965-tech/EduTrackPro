package sa.gheras.edutrack.ui.teacher

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import java.io.File
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.delay
import sa.gheras.edutrack.data.entity.RoomEntity
import sa.gheras.edutrack.ui.common.Num
import sa.gheras.edutrack.ui.teacher.media.AudioPlayerHelper
import sa.gheras.edutrack.ui.teacher.media.AudioRecorderHelper
import sa.gheras.edutrack.ui.teacher.media.MediaAttachment

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateAssignmentDialog(
    rooms: List<RoomEntity>,
    onDismiss: () -> Unit,
    onSubmit: (
        title: String,
        subject: String?,
        roomId: String?,
        dueDate: LocalDate,
        instructions: String?,
        pageRef: String?,
        attachments: List<MediaAttachment>
    ) -> Unit
) {
    val context = LocalContext.current

    var title by remember { mutableStateOf("") }
    var selectedSubject by remember { mutableStateOf("القرآن") }
    var selectedRoomId by remember { mutableStateOf(rooms.firstOrNull()?.id) }
    var dueDate by remember { mutableStateOf(LocalDate.now().plusDays(1)) }
    var pageRef by remember { mutableStateOf("") }
    var instructions by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Attachments list
    val attachments = remember { mutableStateListOf<MediaAttachment>() }

    // Link insertion prompt dialog
    var showLinkDialog by remember { mutableStateOf(false) }
    var linkUrl by remember { mutableStateOf("") }
    var linkTitle by remember { mutableStateOf("") }
    var linkKind by remember { mutableStateOf("google_form") }

    // Persistent storage directory for media attachments
    val mediaDir = remember {
        File(context.filesDir, "assignments_media").apply { mkdirs() }
    }

    // Audio recording state
    val audioRecorder = remember { AudioRecorderHelper(context) }
    val audioPlayer = remember { AudioPlayerHelper() }
    var isRecording by remember { mutableStateOf(false) }
    var currentAudioFile by remember { mutableStateOf<File?>(null) }
    var recordDurationSeconds by remember { mutableStateOf(0) }
    var playingVoiceId by remember { mutableStateOf<String?>(null) }

    val saveRecording: (File?, Int) -> Unit = remember {
        { file, duration ->
            if (file != null && file.exists() && file.length() > 0) {
                attachments.add(
                    MediaAttachment(
                        id = UUID.randomUUID().toString(),
                        kind = "voice_note",
                        title = "تسجيل تلاوة / شرح صوتي",
                        uri = file.absolutePath,
                        durationSeconds = duration
                    )
                )
            }
        }
    }

    val copyUriToPersistentFile: (Uri, String, String) -> String = remember {
        { uri, prefix, ext ->
            try {
                val target = File(mediaDir, "${prefix}_${System.currentTimeMillis()}.$ext")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                target.absolutePath
            } catch (_: Exception) {
                uri.toString()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            audioRecorder.release()
            audioPlayer.release()
        }
    }

    // Recording timer
    LaunchedEffect(isRecording) {
        if (isRecording) {
            recordDurationSeconds = 0
            while (isRecording) {
                delay(1000)
                recordDurationSeconds++
                if (recordDurationSeconds >= 180) { // 3 minutes max
                    val duration = audioRecorder.stopRecording()
                    isRecording = false
                    saveRecording(currentAudioFile, duration)
                    currentAudioFile = null
                    break
                }
            }
        }
    }

    // Permission launcher for microphone
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val audioFile = File(mediaDir, "voice_${System.currentTimeMillis()}.m4a")
            currentAudioFile = audioFile
            if (audioRecorder.startRecording(audioFile)) {
                isRecording = true
            }
        } else {
            errorMessage = "يرجى منح إذن الميكروفون لتسجيل الصوت"
        }
    }

    // Pick Image launcher
    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val persistentPath = copyUriToPersistentFile(uri, "img", "jpg")
            attachments.add(
                MediaAttachment(
                    id = UUID.randomUUID().toString(),
                    kind = "image",
                    title = "ورقة عمل / صورة",
                    uri = persistentPath
                )
            )
        }
    }

    // Pick PDF launcher
    val pdfPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val persistentPath = copyUriToPersistentFile(uri, "doc", "pdf")
            attachments.add(
                MediaAttachment(
                    id = UUID.randomUUID().toString(),
                    kind = "pdf_document",
                    title = "مستند PDF للواجب",
                    uri = persistentPath
                )
            )
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .padding(vertical = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Assignment,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Text(
                            text = "إضافة تكليف / واجب جديد",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "إغلاق")
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Error alert
                if (!errorMessage.isNullOrBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = errorMessage ?: "",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                // Title Input
                OutlinedTextField(
                    value = title,
                    onValueChange = {
                        title = it
                        errorMessage = null
                    },
                    label = { Text("عنوان التكليف أو الواجب *") },
                    placeholder = { Text("مثال: حفظ سورة النبأ من 1 إلى 15") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Subject Selection
                Text(
                    text = "المادة الدراسية:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                val subjects = listOf("القرآن", "لغتي", "الإنجليزي", "الرياضيات", "عام")
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(subjects) { subj ->
                        FilterChip(
                            selected = selectedSubject == subj,
                            onClick = { selectedSubject = subj },
                            label = { Text(subj) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Room Selection
                if (rooms.isNotEmpty()) {
                    Text(
                        text = "القاعة المستهدفة:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(rooms) { room ->
                            FilterChip(
                                selected = selectedRoomId == room.id,
                                onClick = { selectedRoomId = room.id },
                                label = { Text("قاعة: ${room.name}") }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Due Date Quick Selection
                Text(
                    text = "موعد التسليم: (${dueDate.year}-${String.format(Locale.US, "%02d", dueDate.monthValue)}-${String.format(Locale.US, "%02d", dueDate.dayOfMonth)})",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val today = LocalDate.now()
                    listOf(
                        "اليوم" to today,
                        "غداً" to today.plusDays(1),
                        "بعد يومين" to today.plusDays(2),
                        "بعد أسبوع" to today.plusDays(7)
                    ).forEach { (label, date) ->
                        FilterChip(
                            selected = dueDate == date,
                            onClick = { dueDate = date },
                            label = { Text(label) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Page Reference
                OutlinedTextField(
                    value = pageRef,
                    onValueChange = { pageRef = it },
                    label = { Text("رقم الصفحة أو موضع الآيات (اختياري)") },
                    placeholder = { Text("مثال: ص 24 أو الآيات 1 - 20") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Instructions
                OutlinedTextField(
                    value = instructions,
                    onValueChange = { instructions = it },
                    label = { Text("التعليمات والتوجيهات للطلاب") },
                    placeholder = { Text("أدخل تفاصيل التكليف أو طريقة الحل...") },
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                // ==================== MULTIMEDIA ATTACHMENTS ====================
                Text(
                    text = "المرفقات والوسائط التفاعلية:",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Action Bar for Media
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // 1. Voice Record Button
                    Button(
                        onClick = {
                            if (isRecording) {
                                val duration = audioRecorder.stopRecording()
                                isRecording = false
                                saveRecording(currentAudioFile, duration)
                                currentAudioFile = null
                            } else {
                                val hasPermission = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                if (hasPermission) {
                                    val audioFile = File(mediaDir, "voice_${System.currentTimeMillis()}.m4a")
                                    currentAudioFile = audioFile
                                    if (audioRecorder.startRecording(audioFile)) {
                                        isRecording = true
                                    }
                                } else {
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primaryContainer,
                            contentColor = if (isRecording) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isRecording) "إيقاف (${Num.formatInt(recordDurationSeconds)}ث)" else "صوت 🎙️",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }

                    // 2. Google Form / Link
                    OutlinedButton(
                        onClick = {
                            linkKind = "google_form"
                            linkTitle = "نموذج Google Form"
                            linkUrl = ""
                            showLinkDialog = true
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("نموذج 📝", style = MaterialTheme.typography.labelSmall)
                    }

                    // 3. Image
                    OutlinedButton(
                        onClick = { imagePickerLauncher.launch("image/*") },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("صورة 📷", style = MaterialTheme.typography.labelSmall)
                    }

                    // 4. PDF
                    OutlinedButton(
                        onClick = { pdfPickerLauncher.launch("application/pdf") },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("ملف 📄", style = MaterialTheme.typography.labelSmall)
                    }
                }

                // Render attached items list
                if (attachments.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        attachments.forEachIndexed { index, att ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        val icon = when (att.kind) {
                                            "voice_note" -> Icons.Default.Mic
                                            "google_form" -> Icons.Default.Assignment
                                            "pdf_document" -> Icons.Default.Description
                                            "image" -> Icons.Default.Image
                                            "video_link" -> Icons.Default.Videocam
                                            else -> Icons.Default.AddLink
                                        }
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )

                                        Column {
                                            Text(
                                                text = att.title,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            if (att.durationSeconds != null) {
                                                Text(
                                                    text = "المدة: ${Num.formatInt(att.durationSeconds)} ثانية",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.outline
                                                )
                                            } else if (att.uri.startsWith("http")) {
                                                Text(
                                                    text = att.uri.take(40) + "...",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.outline
                                                )
                                            }
                                        }
                                    }

                                    // If voice note: preview play
                                    if (att.kind == "voice_note") {
                                        val isThisPlaying = playingVoiceId == att.id
                                        IconButton(
                                            onClick = {
                                                if (isThisPlaying) {
                                                    audioPlayer.stop()
                                                    playingVoiceId = null
                                                } else {
                                                    audioPlayer.play(att.uri) {
                                                        playingVoiceId = null
                                                    }
                                                    playingVoiceId = att.id
                                                }
                                            },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (isThisPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }

                                    // Delete attachment
                                    IconButton(
                                        onClick = {
                                            if (playingVoiceId == att.id) {
                                                audioPlayer.stop()
                                                playingVoiceId = null
                                            }
                                            attachments.removeAt(index)
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "حذف المرفق",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Dialog Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("إلغاء")
                    }

                    Button(
                        onClick = {
                            if (title.isBlank()) {
                                errorMessage = "يرجى كتابة عنوان التكليف"
                                return@Button
                            }
                            onSubmit(
                                title.trim(),
                                selectedSubject,
                                selectedRoomId,
                                dueDate,
                                instructions.trim(),
                                pageRef.trim().ifBlank { null },
                                attachments.toList()
                            )
                        },
                        modifier = Modifier.weight(1.5f)
                    ) {
                        Text("حفظ وإسناد التكليف ✨", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // Google Form / Link prompt dialog
    if (showLinkDialog) {
        AlertDialog(
            onDismissRequest = { showLinkDialog = false },
            title = { Text("إرفاق رابط نموذج أو فيديو") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = linkTitle,
                        onValueChange = { linkTitle = it },
                        label = { Text("عنوان الرابط") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = linkUrl,
                        onValueChange = { linkUrl = it },
                        label = { Text("الرابط (URL)") },
                        placeholder = { Text("https://forms.gle/... أو https://youtu.be/...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmedUrl = linkUrl.trim()
                        if (trimmedUrl.isNotBlank()) {
                            val detectedKind = when {
                                trimmedUrl.contains("forms.gle") || trimmedUrl.contains("docs.google.com/forms") -> "google_form"
                                trimmedUrl.contains("youtube.com") || trimmedUrl.contains("youtu.be") -> "video_link"
                                else -> "web_link"
                            }
                            val effectiveTitle = linkTitle.trim().ifBlank {
                                when (detectedKind) {
                                    "google_form" -> "نموذج Google Form"
                                    "video_link" -> "فيديو شرح"
                                    else -> "رابط تعليمي"
                                }
                            }
                            attachments.add(
                                MediaAttachment(
                                    id = UUID.randomUUID().toString(),
                                    kind = detectedKind,
                                    title = effectiveTitle,
                                    uri = trimmedUrl
                                )
                            )
                        }
                        showLinkDialog = false
                    }
                ) {
                    Text("إضافة")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLinkDialog = false }) {
                    Text("إلغاء")
                }
            }
        )
    }
}
