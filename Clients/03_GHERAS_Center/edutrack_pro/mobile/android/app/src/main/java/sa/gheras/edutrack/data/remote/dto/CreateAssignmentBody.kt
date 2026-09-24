package sa.gheras.edutrack.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CreateAssignmentBody(
    val id: String? = null,
    val title: String,
    val subject: String? = null,
    val kind: String = "homework",
    @SerialName("due_date") val dueDate: String,
    val instructions: String? = null,
    @SerialName("page_ref") val pageRef: String? = null,
    @SerialName("student_ids") val studentIds: List<String> = emptyList()
)
