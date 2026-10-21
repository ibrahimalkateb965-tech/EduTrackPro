package sa.gheras.edutrack.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UploadResponseDto(
    @SerialName("storage_key") val storageKey: String? = null,
    val url: String,
    @SerialName("file_name") val filename: String? = null,
    val bytes: Long? = null,
    @SerialName("media_type") val mediaType: String? = null,
    val size: Long? = null
)
