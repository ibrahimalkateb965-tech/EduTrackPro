package sa.gheras.edutrack.ui.notifications

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import sa.gheras.edutrack.data.entity.RoomEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateBroadcastDialog(
    rooms: List<RoomEntity>,
    onDismiss: () -> Unit,
    onSend: (
        title: String,
        body: String?,
        priority: String,
        roomId: String?,
        studentIds: List<String>,
        includeGuardians: Boolean,
        includeStudents: Boolean
    ) -> Unit,
    modifier: Modifier = Modifier
) {
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf("normal") }
    var selectedRoomId by remember { mutableStateOf(rooms.firstOrNull()?.id) }
    var includeGuardians by remember { mutableStateOf(true) }
    var includeStudents by remember { mutableStateOf(true) }
    var roomDropdownExpanded by remember { mutableStateOf(false) }

    val selectedRoomName = rooms.find { it.id == selectedRoomId }?.name ?: "كل القاعات"

    val isSendEnabled = title.isNotBlank() && (includeGuardians || includeStudents)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Campaign,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "إرسال تعميم جديد",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Title
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("عنوان التعميم *") },
                    placeholder = { Text("مثال: اختبار قصير يوم الأحد") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Body
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("نص التعميم / التفاصيل") },
                    placeholder = { Text("اكتب تفاصيل الإعلان هنا...") },
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )

                // Room Picker
                if (rooms.isNotEmpty()) {
                    Text(
                        text = "القاعة المستهدفة:",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { roomDropdownExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(selectedRoomName)
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                            }
                        }
                        DropdownMenu(
                            expanded = roomDropdownExpanded,
                            onDismissRequest = { roomDropdownExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("كل القاعات") },
                                onClick = {
                                    selectedRoomId = null
                                    roomDropdownExpanded = false
                                }
                            )
                            rooms.forEach { room ->
                                DropdownMenuItem(
                                    text = { Text(room.name) },
                                    onClick = {
                                        selectedRoomId = room.id
                                        roomDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                // Priority FilterChips
                Text(
                    text = "درجة الأهمية:",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = priority == "normal",
                        onClick = { priority = "normal" },
                        label = { Text("عادي") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                    FilterChip(
                        selected = priority == "urgent",
                        onClick = { priority = "urgent" },
                        label = { Text("عاجل") },
                        leadingIcon = {
                            if (priority == "urgent") {
                                Icon(Icons.Default.NotificationsActive, contentDescription = null)
                            }
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    )
                }

                // Audience Checkboxes
                Text(
                    text = "الجمهور المستهدف:",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { includeGuardians = !includeGuardians }
                ) {
                    Checkbox(
                        checked = includeGuardians,
                        onCheckedChange = { includeGuardians = it }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("أولياء الأمور")
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { includeStudents = !includeStudents }
                ) {
                    Checkbox(
                        checked = includeStudents,
                        onCheckedChange = { includeStudents = it }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("الطلاب")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSend(
                        title.trim(),
                        body.trim().takeIf { it.isNotBlank() },
                        priority,
                        selectedRoomId,
                        emptyList(),
                        includeGuardians,
                        includeStudents
                    )
                },
                enabled = isSendEnabled
            ) {
                Text("إرسال التعميم")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("إلغاء")
            }
        },
        modifier = modifier
    )
}
