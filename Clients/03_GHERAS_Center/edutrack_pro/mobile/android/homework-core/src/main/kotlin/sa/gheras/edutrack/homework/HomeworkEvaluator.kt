package sa.gheras.edutrack.homework

import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.min

class HomeworkEvaluator {
    fun evaluate(
        rubric: Rubric,
        marks: TeacherMarks,
        submission: Submission,
    ): EvaluationResult {
        require(submission.pageCount > 0) { "Submission must contain at least one page" }
        validateRubric(rubric)

        val criteriaByKey = rubric.criteria.associateBy { it.key }
        require(marks.scores.keys.all { it in criteriaByKey }) { "Marks contain an unknown criterion" }

        val breakdown = rubric.criteria.associate { criterion ->
            val score = marks.scores[criterion.key] ?: 0.0
            require(score in 0.0..criterion.maxScore) { "Score is outside the criterion range" }
            criterion.key to (score / criterion.maxScore * criterion.weight * 100.0)
        }
        val unpenalizedTotal = breakdown.values.sum()
        val lateDays = max(0, ChronoUnit.DAYS.between(submission.dueDate, submission.submittedAt)).toInt()
        val latePenalty = min(20.0, lateDays * 5.0)
        val total = max(0.0, unpenalizedTotal - latePenalty)

        return EvaluationResult(
            total = total,
            level = levelFor(total),
            latePenaltyApplied = latePenalty,
            breakdown = breakdown,
        )
    }

    private fun validateRubric(rubric: Rubric) {
        require(rubric.criteria.isNotEmpty()) { "Rubric must contain criteria" }
        require(rubric.criteria.all { it.key.isNotBlank() }) { "Criterion key must not be blank" }
        require(rubric.criteria.map { it.key }.distinct().size == rubric.criteria.size) {
            "Criterion keys must be unique"
        }
        require(rubric.criteria.all { it.weight in 0.0..1.0 }) { "Criterion weight must be between 0 and 1" }
        require(rubric.criteria.all { it.maxScore > 0.0 }) { "Criterion maxScore must be positive" }
        val weightSum = rubric.criteria.sumOf { it.weight }
        require(kotlin.math.abs(weightSum - 1.0) <= WEIGHT_TOLERANCE + FLOATING_POINT_MARGIN) {
            "Criterion weights must sum to 1.0 within tolerance"
        }
    }

    private fun levelFor(total: Double): EvaluationLevel = when {
        total >= 90.0 -> EvaluationLevel.EXCELLENT
        total >= 80.0 -> EvaluationLevel.PROFICIENT
        total >= 70.0 -> EvaluationLevel.GOOD
        total >= 50.0 -> EvaluationLevel.NEEDS_FOLLOW_UP
        else -> EvaluationLevel.NEEDS_SUPPORT
    }

    companion object {
        const val WEIGHT_TOLERANCE = 0.001
        private const val FLOATING_POINT_MARGIN = 1e-12
    }
}
