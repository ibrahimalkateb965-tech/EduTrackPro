package sa.gheras.edutrack.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class StudentDto(
    val id: String,
    val name: String,
    @SerialName("birth_date") val birthDate: String? = null,
    val nationality: String? = null,
    val gender: String? = null,
    @SerialName("room_id") val roomId: String? = null,
    @SerialName("room_name") val roomName: String? = null,
    @SerialName("group_name") val groupName: String? = null,
    val status: String? = null,
    @SerialName("has_difficulties") val hasDifficulties: Boolean? = null,
    @SerialName("difficulty_notes") val difficultyNotes: String? = null,
    @SerialName("child_notes") val childNotes: String? = null,
    @SerialName("guardian_phone") val guardianPhone: String? = null,
    @SerialName("guardian_relation") val guardianRelation: String? = null
)
