package sa.gheras.edutrack.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest

/**
 * Reusable student avatar composable with remote image loading and elegant fallback to initials or person icon.
 */
@Composable
fun StudentAvatar(
    name: String,
    photoUrl: String?,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    shape: Shape = CircleShape,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.primary
) {
    val resolvedUrl = remember(photoUrl) { PhotoUtils.resolveUrl(photoUrl) }
    var isImageError by remember(resolvedUrl) { mutableStateOf(false) }

    Surface(
        shape = shape,
        color = containerColor,
        modifier = modifier.size(size)
    ) {
        if (!resolvedUrl.isNullOrBlank() && !isImageError) {
            val context = LocalContext.current
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(resolvedUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = name.ifBlank { null },
                contentScale = ContentScale.Crop,
                onError = { isImageError = true },
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
            )
        } else {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                val initial = name.trim().firstOrNull()?.toString()
                if (initial != null && initial.isNotBlank()) {
                    val fontSize = (size.value * 0.42f).sp
                    Text(
                        text = initial,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = fontSize,
                            fontWeight = FontWeight.Bold
                        ),
                        color = contentColor
                    )
                } else {
                    val iconSize = (size.value * 0.55f).dp
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(iconSize)
                    )
                }
            }
        }
    }
}
