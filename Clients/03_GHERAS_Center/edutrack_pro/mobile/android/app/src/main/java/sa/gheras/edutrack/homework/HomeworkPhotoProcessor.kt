package sa.gheras.edutrack.homework

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class HomeworkPhotoProcessor(
    private val planner: CompressionPlanner = CompressionPlanner(),
    private val compressor: ImageCompressor = ImageCompressor()
) {
    suspend fun processUri(context: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("Cannot read image from $uri")

        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, options)

        val spec = ImageSpec(
            width = if (options.outWidth > 0) options.outWidth else 1600,
            height = if (options.outHeight > 0) options.outHeight else 1200,
            bytes = rawBytes.size.toLong()
        )
        val plan = planner.plan(spec)
        val compressed = compressor.compress(rawBytes, plan)
        compressed.bytes
    }

    suspend fun processPhotos(context: Context, uris: List<Uri>): List<ByteArray> = withContext(Dispatchers.IO) {
        uris.map { uri -> processUri(context, uri) }
    }
}
