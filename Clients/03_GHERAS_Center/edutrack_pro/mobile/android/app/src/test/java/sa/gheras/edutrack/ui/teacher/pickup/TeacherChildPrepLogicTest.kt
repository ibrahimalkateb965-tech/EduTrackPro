package sa.gheras.edutrack.ui.teacher.pickup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sa.gheras.edutrack.data.remote.dto.PickupStatus

class TeacherChildPrepLogicTest {

    @Test
    fun `canMarkReady returns true exclusively for IN_PREP status`() {
        assertTrue(canMarkReady(PickupStatus.IN_PREP))
    }

    @Test
    fun `canMarkReady returns false for null and all other statuses`() {
        assertFalse(canMarkReady(null))
        assertFalse(canMarkReady(PickupStatus.NOTIFIED))
        assertFalse(canMarkReady(PickupStatus.READY))
        assertFalse(canMarkReady(PickupStatus.COMPLETED))
        assertFalse(canMarkReady(PickupStatus.CANCELLED))
        assertFalse(canMarkReady(PickupStatus.FAILED))
    }
}
