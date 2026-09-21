package sa.gheras.edutrack.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AttendanceItemBody(
    @SerialName("student_id") val studentId: String,
    val date: String,
    val status: String,
    val note: String? = null
)

@Serializable
data class DailyEvalItemBody(
    @SerialName("student_id") val studentId: String,
    val date: String,
    val subject: String? = null,
    val value: String? = null,
    val score: Double? = null,
    val notes: String? = null
)

@Serializable
data class LessonLogBody(
    @SerialName("schedule_id") val scheduleId: String,
    val date: String,
    val status: String,
    val covered: String? = null,
    val homework: String? = null,
    val notes: String? = null
)
