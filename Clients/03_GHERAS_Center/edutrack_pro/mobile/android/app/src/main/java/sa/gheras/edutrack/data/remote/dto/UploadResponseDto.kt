package sa.gheras.edutrack.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class UploadResponseDto(
    val url: String,
    val filename: String,
    val size: Long
)
