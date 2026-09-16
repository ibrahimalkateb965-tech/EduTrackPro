package sa.gheras.edutrack.homework

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HomeworkEvaluatorTest {
    private val evaluator = HomeworkEvaluator()
    private val dueDate = LocalDate.of(2026, 9, 1)

    @Test
    fun `rejects weights outside the required sum tolerance`() {
        assertFailsWith<IllegalArgumentException> { evaluateWithWeights(0.5, 0.498) }
        assertFailsWith<IllegalArgumentException> { evaluateWithWeights(0.5, 0.502) }
    }

    @Test
    fun `accepts weights at the required sum tolerance`() {
        evaluateWithWeights(0.5, 0.499)
        evaluateWithWeights(0.5, 0.501)
    }

    @Test
    fun `rejects individual weights outside zero through one`() {
        listOf(-0.1, 1.1).forEach { invalidWeight ->
            assertFailsWith<IllegalArgumentException> {
                evaluator.evaluate(
                    Rubric(listOf(Criterion("work", invalidWeight, 100.0))),
                    TeacherMarks(mapOf("work" to 100.0)),
                    Submission(dueDate, dueDate, 1),
                )
            }
        }
    }

    @Test
    fun `assigns levels at every stated boundary`() {
        val cases = listOf(
            49.99 to EvaluationLevel.NEEDS_SUPPORT,
            50.0 to EvaluationLevel.NEEDS_FOLLOW_UP,
            69.99 to EvaluationLevel.NEEDS_FOLLOW_UP,
            70.0 to EvaluationLevel.GOOD,
            79.99 to EvaluationLevel.GOOD,
            80.0 to EvaluationLevel.PROFICIENT,
            89.99 to EvaluationLevel.PROFICIENT,
            90.0 to EvaluationLevel.EXCELLENT,
        )
        cases.forEach { (score, expectedLevel) ->
            val result = evaluator.evaluate(
                Rubric(listOf(Criterion("work", 1.0, 100.0))),
                TeacherMarks(mapOf("work" to score)),
                Submission(dueDate, dueDate, 1),
            )
            assertEquals(expectedLevel, result.level, "score=$score")
        }
    }

    @Test
    fun `applies five points per late day and caps the penalty at twenty`() {
        val rubric = Rubric(listOf(Criterion("work", 1.0, 100.0)))
        val marks = TeacherMarks(mapOf("work" to 100.0))
        val twoDaysLate = evaluator.evaluate(rubric, marks, Submission(dueDate.plusDays(2), dueDate, 1))
        val tenDaysLate = evaluator.evaluate(rubric, marks, Submission(dueDate.plusDays(10), dueDate, 1))
        assertEquals(10.0, twoDaysLate.latePenaltyApplied)
        assertEquals(90.0, twoDaysLate.total)
        assertEquals(20.0, tenDaysLate.latePenaltyApplied)
        assertEquals(80.0, tenDaysLate.total)
    }

    @Test
    fun `does not penalize an on-time or early submission`() {
        val rubric = Rubric(listOf(Criterion("work", 1.0, 100.0)))
        val marks = TeacherMarks(mapOf("work" to 100.0))
        listOf(dueDate, dueDate.minusDays(1)).forEach { submittedAt ->
            val result = evaluator.evaluate(rubric, marks, Submission(submittedAt, dueDate, 1))
            assertEquals(0.0, result.latePenaltyApplied)
        }
    }

    @Test
    fun `never reduces a result below zero`() {
        val result = evaluator.evaluate(
            Rubric(listOf(Criterion("work", 1.0, 100.0))),
            TeacherMarks(mapOf("work" to 5.0)),
            Submission(dueDate.plusDays(10), dueDate, 1),
        )
        assertEquals(0.0, result.total)
    }

    @Test
    fun `rejects zero and negative page counts`() {
        listOf(0, -1).forEach { pageCount ->
            assertFailsWith<IllegalArgumentException> {
                evaluator.evaluate(
                    Rubric(listOf(Criterion("work", 1.0, 100.0))),
                    TeacherMarks(mapOf("work" to 100.0)),
                    Submission(dueDate, dueDate, pageCount),
                )
            }
        }
    }

    private fun evaluateWithWeights(first: Double, second: Double) {
        evaluator.evaluate(
            Rubric(listOf(Criterion("one", first, 10.0), Criterion("two", second, 10.0))),
            TeacherMarks(mapOf("one" to 10.0, "two" to 10.0)),
            Submission(dueDate, dueDate, 1),
        )
    }
}
