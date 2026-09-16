package sa.gheras.edutrack.homework

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

data class CompressedImage(
    val bytes: ByteArray,
    val imageSpec: ImageSpec,
)

class ImageCompressor {
    fun compress(source: ByteArray, plan: CompressionPlan): CompressedImage {
        require(source.isNotEmpty()) { "Image data must not be empty" }

        val options = BitmapFactory.Options().apply {
            inSampleSize = plan.inSampleSize
        }
        val decoded = requireNotNull(BitmapFactory.decodeByteArray(source, 0, source.size, options)) {
            "Image data cannot be decoded"
        }
        val oriented = decoded.applyOrientation(readOrientation(source))
        if (oriented !== decoded) decoded.recycle()
        val scaled = if (oriented.width == plan.targetWidth && oriented.height == plan.targetHeight) {
            oriented
        } else {
            Bitmap.createScaledBitmap(oriented, plan.targetWidth, plan.targetHeight, true).also {
                oriented.recycle()
            }
        }

        try {
            var output = ByteArray(0)
            for (quality in plan.qualitySteps) {
                output = ByteArrayOutputStream().use { stream ->
                    check(scaled.compress(Bitmap.CompressFormat.JPEG, quality, stream))
                    stream.toByteArray()
                }
                if (output.size.toLong() <= plan.maxBytes) break
            }
            return CompressedImage(output, ImageSpec(scaled.width, scaled.height, output.size.toLong()))
        } finally {
            scaled.recycle()
        }
    }

    private fun readOrientation(source: ByteArray): Int = ByteArrayInputStream(source).use { input ->
        ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }

    private fun Bitmap.applyOrientation(orientation: Int): Bitmap = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> rotated(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> rotated(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> rotated(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> transformed(-1f, 1f, 0f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> transformed(1f, -1f, 0f)
        ExifInterface.ORIENTATION_TRANSPOSE -> transformed(-1f, 1f, 90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> transformed(-1f, 1f, 270f)
        else -> this
    }

    private fun Bitmap.rotated(degrees: Float): Bitmap = transformed(1f, 1f, degrees)

    private fun Bitmap.transformed(scaleX: Float, scaleY: Float, degrees: Float): Bitmap {
        val matrix = android.graphics.Matrix().apply {
            postScale(scaleX, scaleY)
            postRotate(degrees)
        }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }
}
