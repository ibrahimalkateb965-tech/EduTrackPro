package sa.gheras.edutrack.homework

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

class CompressionPlanner(
    private val maxLongEdge: Int = DEFAULT_MAX_LONG_EDGE,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val qualitySteps: List<Int> = DEFAULT_QUALITY_STEPS,
) {
    init {
        require(maxLongEdge > 0) { "maxLongEdge must be positive" }
        require(maxBytes > 0) { "maxBytes must be positive" }
        require(qualitySteps.isNotEmpty()) { "qualitySteps must not be empty" }
        require(qualitySteps.all { it in 0..100 }) { "qualitySteps must be between 0 and 100" }
    }

    fun plan(source: ImageSpec): CompressionPlan {
        require(source.width > 0) { "Image width must be positive" }
        require(source.height > 0) { "Image height must be positive" }
        require(source.bytes >= 0) { "Image byte count must not be negative" }

        val longEdge = max(source.width, source.height)
        val scaleFactor = min(1.0, maxLongEdge.toDouble() / longEdge)
        val targetWidth = max(1, floor(source.width * scaleFactor).toInt())
        val targetHeight = max(1, floor(source.height * scaleFactor).toInt())

        return CompressionPlan(
            scaleFactor = scaleFactor,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            inSampleSize = sampleSizeFor(source.width, source.height, targetWidth, targetHeight),
            maxBytes = maxBytes,
            qualitySteps = qualitySteps.toList(),
        )
    }

    private fun sampleSizeFor(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): Int {
        var sampleSize = 1
        while (
            sourceWidth / (sampleSize * 2) >= targetWidth &&
            sourceHeight / (sampleSize * 2) >= targetHeight
        ) {
            sampleSize *= 2
        }
        return sampleSize
    }

    companion object {
        const val DEFAULT_MAX_LONG_EDGE = 1600
        const val DEFAULT_MAX_BYTES = 600L * 1024L
        val DEFAULT_QUALITY_STEPS: List<Int> = (85 downTo 50 step 5).toList()
    }
}
