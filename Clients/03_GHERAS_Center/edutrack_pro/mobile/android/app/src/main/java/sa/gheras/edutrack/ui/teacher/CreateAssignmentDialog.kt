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
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.core.content.FileProvider
import java.io.File
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
        attachments: List<MediaAttachment>,
        onError: (String) -> Unit
    ) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var title by remember { mutableStateOf("") }
    var selectedSubject by remember { mutableStateOf("القرآن") }
    var selectedRoomId by remember { mutableStateOf(rooms.firstOrNull()?.id) }
    var dueDate by remember { mutableStateOf(LocalDate.now().plusDays(1)) }
    var pageRef by remember { mutableStateOf("") }
    var instructions by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }

    // Attachments list
    val attachments = remember { mutableStateListOf<MediaAttachment>() }

    // Link insertion prompt dialog
    var showLinkDialog by remember { mutableStateOf(false) }
    var linkUrl by remember { mutableStateOf("") }
    var linkTitle by remember { mutableStateOf("") }
    var linkKind by remember { mutableStateOf("google_form") }

    // Image source selection dialog & camera capture state
    var showImageSourceDialog by remember { mutableStateOf(false) }
    var tempCameraFile by remember { mutableStateOf<File?>(null) }

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

    val saveRecording: (File?, Int) -> Unit = { file, duration ->
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
            if (title.isBlank()) {
                title = "تسجيل صوتي - $selectedSubject"
            }
            errorMessage = null
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

    // Camera capture launcher
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            val file = tempCameraFile
            if (file != null && file.exists() && file.length() > 0) {
                attachments.add(
                    MediaAttachment(
                        id = UUID.randomUUID().toString(),
                        kind = "image",
                        title = "صورة بالكاميرا / ورقة عمل",
                        uri = file.absolutePath
                    )
                )
            }
        }
        tempCameraFile = null
    }

    val launchCamera: () -> Unit = {
        try {
            val file = File(mediaDir, "img_cam_${System.currentTimeMillis()}.jpg")
            tempCameraFile = file
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            cameraLauncher.launch(uri)
        } catch (e: Exception) {
            errorMessage = "تعذر تشغيل الكاميرا: ${e.message}"
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
                    .verticalScroll(scrollState)
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
                            onClick = {
                                val oldSubject = selectedSubject
                                selectedSubject = subj
                                if (title.isBlank() || title == "تسجيل صوتي - $oldSubject") {
                                    if (attachments.any { it.kind == "voice_note" }) {
                                        title = "تسجيل صوتي - $subj"
                                    }
                                }
                            },
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
                        onClick = { showImageSourceDialog = true },
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

                Spacer(modifier = Modifier.height(14.dp))

                // Bottom Error alert (visible when scrolled to bottom)
                if (!errorMessage.isNullOrBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = errorMessage ?: "",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                // Dialog Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        enabled = !isSubmitting,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("إلغاء")
                    }

                    Button(
                        onClick = {
                            val effectiveTitle = title.trim().ifBlank {
                                if (attachments.any { it.kind == "voice_note" }) {
                                    "تسجيل صوتي - $selectedSubject"
                                } else {
                                    "تكليف $selectedSubject"
                                }
                            }
                            if (effectiveTitle.isBlank()) {
                                errorMessage = "يرجى كتابة عنوان التكليف"
                                coroutineScope.launch { scrollState.animateScrollTo(0) }
                                return@Button
                            }
                            isSubmitting = true
                            errorMessage = null
                            val effectiveSubject = if (selectedSubject == "عام") null else selectedSubject
                            val effectiveRoomId = selectedRoomId ?: rooms.firstOrNull()?.id
                            onSubmit(
                                effectiveTitle,
                                effectiveSubject,
                                effectiveRoomId,
                                dueDate,
                                instructions.trim(),
                                pageRef.trim().ifBlank { null },
                                attachments.toList()
                            ) { err ->
                                isSubmitting = false
                                errorMessage = err
                                coroutineScope.launch { scrollState.animateScrollTo(0) }
                            }
                        },
                        enabled = !isSubmitting,
                        modifier = Modifier.weight(1.5f)
                    ) {
                        if (isSubmitting) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("جاري الحفظ...", fontWeight = FontWeight.Bold)
                        } else {
                            Text("حفظ وإسناد التكليف ✨", fontWeight = FontWeight.Bold)
                        }
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

    // Image Source Selection Dialog (Camera vs Gallery)
    if (showImageSourceDialog) {
        AlertDialog(
            onDismissRequest = { showImageSourceDialog = false },
            title = {
                Text(
                    text = "إرفاق صورة للواجب",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "اختر طريقة إرفاق صورة ورقة العمل أو التكليف:",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    // Option 1: Camera capture
                    Surface(
                        onClick = {
                            showImageSourceDialog = false
                            launchCamera()
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.PhotoCamera,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "التقاط صورة بالكاميرا 📸",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "تصوير ورقة العمل أو الكتاب فورياً",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // Option 2: Choose from gallery
                    Surface(
                        onClick = {
                            showImageSourceDialog = false
                            imagePickerLauncher.launch("image/*")
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.PhotoLibrary,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "اختيار من المعرض 🖼️",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "اختيار صورة محفوظة في ألبوم الجهاز",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showImageSourceDialog = false }) {
                    Text("إلغاء")
                }
            }
        )
    }
}
