package sa.gheras.edutrack.ui.guardian

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import sa.gheras.edutrack.data.entity.StudentEntity

@Composable
fun ChildSwitcher(
    children: List<StudentEntity>,
    selectedChildId: String?,
    onChildSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (children.size <= 1) return

    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        items(children, key = { it.id }) { child ->
            val isSelected = child.id == selectedChildId
            FilterChip(
                selected = isSelected,
                onClick = { onChildSelected(child.id) },
                leadingIcon = {
                    Icon(Icons.Default.Face, contentDescription = null)
                },
                label = { Text(child.name) }
            )
        }
    }
}
