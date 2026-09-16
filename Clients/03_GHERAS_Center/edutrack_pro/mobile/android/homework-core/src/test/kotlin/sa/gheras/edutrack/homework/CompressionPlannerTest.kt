package sa.gheras.edutrack.homework

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompressionPlannerTest {
    private val planner = CompressionPlanner()

    @Test
    fun `plans portrait images within the long edge limit`() {
        val plan = planner.plan(ImageSpec(1200, 2400, 900_000))
        assertEquals(0.6666666666666666, plan.scaleFactor)
        assertEquals(800, plan.targetWidth)
        assertEquals(1600, plan.targetHeight)
    }

    @Test
    fun `plans landscape images within the long edge limit`() {
        val plan = planner.plan(ImageSpec(2400, 1200, 900_000))
        assertEquals(1600, plan.targetWidth)
        assertEquals(800, plan.targetHeight)
    }

    @Test
    fun `keeps tiny images at their original dimensions`() {
        val plan = planner.plan(ImageSpec(320, 240, 10_000))
        assertEquals(1.0, plan.scaleFactor)
        assertEquals(320, plan.targetWidth)
        assertEquals(240, plan.targetHeight)
        assertEquals(1, plan.inSampleSize)
    }

    @Test
    fun `keeps an image exactly at the maximum long edge`() {
        val plan = planner.plan(ImageSpec(1600, 900, 600L * 1024L))
        assertEquals(1.0, plan.scaleFactor)
        assertEquals(1600, plan.targetWidth)
        assertEquals(900, plan.targetHeight)
    }

    @Test
    fun `plans huge images with required byte limit and quality ladder`() {
        val plan = planner.plan(ImageSpec(8000, 6000, 12_000_000))
        assertEquals(1600, plan.targetWidth)
        assertEquals(1200, plan.targetHeight)
        assertEquals(600L * 1024L, plan.maxBytes)
        assertEquals(listOf(85, 80, 75, 70, 65, 60, 55, 50), plan.qualitySteps)
        assertTrue(plan.inSampleSize >= 1)
    }
}
