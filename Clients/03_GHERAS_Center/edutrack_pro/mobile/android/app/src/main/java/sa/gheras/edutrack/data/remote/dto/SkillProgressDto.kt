package sa.gheras.edutrack.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SkillProgressDto(
    val id: String,
    @SerialName("student_id") val studentId: String,
    val subject: String,
    val date: String,
    val score: Double? = null,
    val notes: String? = null,
    @SerialName("student_name") val studentName: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("branch_id") val branchId: String? = null,
    val skill: String? = null,
    val level: String? = null,
    val note: String? = null
)
