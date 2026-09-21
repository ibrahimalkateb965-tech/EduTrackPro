package sa.gheras.edutrack.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Envelope<T>(
    val items: List<T> = emptyList(),
    val total: Int = 0,
    val limit: Int = 100,
    val offset: Int = 0
)

@Serializable
data class ApiErrorBody(
    val detail: String? = null,
    val error: String? = null,
    val message: String? = null,
    val code: String? = null
)
