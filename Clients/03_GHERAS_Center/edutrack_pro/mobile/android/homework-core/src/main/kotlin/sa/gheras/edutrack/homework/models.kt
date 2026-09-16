package sa.gheras.edutrack.homework

import java.time.LocalDate

data class Criterion(
    val key: String,
    val weight: Double,
    val maxScore: Double,
)

data class Rubric(val criteria: List<Criterion>)

data class TeacherMarks(val scores: Map<String, Double>)

data class Submission(
    val submittedAt: LocalDate,
    val dueDate: LocalDate,
    val pageCount: Int,
)

enum class EvaluationLevel(val label: String) {
    EXCELLENT("متميز"),
    PROFICIENT("متقن"),
    GOOD("جيد"),
    NEEDS_FOLLOW_UP("يحتاج متابعة"),
    NEEDS_SUPPORT("يحتاج دعم"),
}

data class EvaluationResult(
    val total: Double,
    val level: EvaluationLevel,
    val latePenaltyApplied: Double,
    val breakdown: Map<String, Double>,
)

data class ImageSpec(
    val width: Int,
    val height: Int,
    val bytes: Long,
)

data class CompressionPlan(
    val scaleFactor: Double,
    val targetWidth: Int,
    val targetHeight: Int,
    val inSampleSize: Int,
    val maxBytes: Long,
    val qualitySteps: List<Int>,
)
