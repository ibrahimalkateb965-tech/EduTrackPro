package sa.gheras.edutrack.ui.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import sa.gheras.edutrack.data.entity.NotificationEntity
import sa.gheras.edutrack.data.local.session.Role
import java.time.Instant
import java.time.LocalDate

/** Robolectric: the resolver parses action URLs with android.net.Uri. */
@RunWith(RobolectricTestRunner::class)
class NotificationDeepLinkTest {

    private val now = Instant.parse("2026-09-24T08:00:00Z")

    private fun notif(
        kind: String = "announcement",
        actionUrl: String? = null,
        targetType: String? = null,
        targetId: String? = null
    ) = NotificationEntity(
        id = "n_1",
        branchId = "b_1",
        userId = "u_1",
        kind = kind,
        title = "عنوان",
        body = "نص",
        targetType = targetType,
        targetId = targetId,
        actionUrl = actionUrl,
        senderName = "أ. سارة",
        readAt = null,
        sentAt = now,
        createdAt = now,
        updatedAt = now
    )

    // ---- 1. actionUrl: assignment ----

    @Test
    fun assignmentUrl_teacher_opensAssignmentByQueryId() {
        val n = notif(actionUrl = "gheras://assignment?id=a_9&student_id=s_1", targetId = "a_other")
        assertEquals(NotificationAction.OpenAssignment("a_9"), NotificationDeepLinkResolver.resolve(n, Role.TEACHER))
    }

    @Test
    fun assignmentUrl_guardianWithStudent_opensChildHomework() {
        // Server producer shape: no id param, assignment id lives in targetId.
        val n = notif(kind = "assignment", actionUrl = "gheras://assignment?student_id=s_1", targetType = "assignment", targetId = "a_1")
        assertEquals(NotificationAction.OpenChildHomework("s_1"), NotificationDeepLinkResolver.resolve(n, Role.GUARDIAN))
    }

    @Test
    fun assignmentUrl_studentWithoutStudentId_fallsBackToAssignment() {
        val n = notif(actionUrl = "gheras://assignment", targetId = "a_1")
        assertEquals(NotificationAction.OpenAssignment("a_1"), NotificationDeepLinkResolver.resolve(n, Role.STUDENT))
    }

    @Test
    fun assignmentUrl_teacherUsesTargetIdWhenQueryIdMissing() {
        val n = notif(actionUrl = "gheras://assignment?student_id=s_1", targetId = "a_1")
        assertEquals(NotificationAction.OpenAssignment("a_1"), NotificationDeepLinkResolver.resolve(n, Role.TEACHER))
    }

    // ---- 2. actionUrl: attendance ----

    @Test
    fun attendanceUrl_guardian_opensChildAttendance() {
        val n = notif(kind = "attendance", actionUrl = "gheras://attendance?student_id=s_7&date=2026-09-20", targetType = "attendance", targetId = "room_1")
        assertEquals(NotificationAction.OpenChildAttendance("s_7"), NotificationDeepLinkResolver.resolve(n, Role.GUARDIAN))
    }

    @Test
    fun attendanceUrl_student_opensChildAttendance() {
        val n = notif(actionUrl = "gheras://attendance?student_id=s_7")
        assertEquals(NotificationAction.OpenChildAttendance("s_7"), NotificationDeepLinkResolver.resolve(n, Role.STUDENT))
    }

    @Test
    fun attendanceUrl_teacherWithRoom_opensRoomAttendanceOnDate() {
        val n = notif(actionUrl = "gheras://attendance?room_id=r_2&date=2026-09-20")
        assertEquals(NotificationAction.OpenAttendance("r_2", "2026-09-20"), NotificationDeepLinkResolver.resolve(n, Role.TEACHER))
    }

    @Test
    fun attendanceUrl_teacherWithoutDate_defaultsToToday() {
        val n = notif(actionUrl = "gheras://attendance?room_id=r_2")
        val action = NotificationDeepLinkResolver.resolve(n, Role.TEACHER)
        assertEquals(NotificationAction.OpenAttendance("r_2", LocalDate.now().toString()), action)
    }

    @Test
    fun attendanceUrl_teacherWithoutRoom_fallsThroughToTarget() {
        val n = notif(actionUrl = "gheras://attendance?student_id=s_7", targetType = "attendance", targetId = "r_3")
        val action = NotificationDeepLinkResolver.resolve(n, Role.TEACHER)
        assertTrue(action is NotificationAction.OpenAttendance)
        assertEquals("r_3", (action as NotificationAction.OpenAttendance).roomId)
    }

    // ---- 3. actionUrl: announcement / external / malformed ----

    @Test
    fun announcementUrl_showsAnnouncementWithSender() {
        val n = notif(actionUrl = "gheras://announcement?student_id=s_1")
        assertEquals(
            NotificationAction.ShowAnnouncement("عنوان", "نص", "أ. سارة"),
            NotificationDeepLinkResolver.resolve(n, Role.GUARDIAN)
        )
    }

    @Test
    fun httpsUrl_opensExternal() {
        val n = notif(kind = "system", actionUrl = "https://gheras.sa/news")
        assertEquals(NotificationAction.OpenExternalUrl("https://gheras.sa/news"), NotificationDeepLinkResolver.resolve(n, Role.GUARDIAN))
    }

    @Test
    fun unknownSchemeAndHost_fallThroughToKind() {
        val n1 = notif(kind = "system", actionUrl = "ftp://x/y")
        assertEquals(NotificationAction.None, NotificationDeepLinkResolver.resolve(n1, Role.GUARDIAN))
        val n2 = notif(kind = "broadcast", actionUrl = "gheras://unknown-host")
        assertTrue(NotificationDeepLinkResolver.resolve(n2, Role.GUARDIAN) is NotificationAction.ShowAnnouncement)
    }

    @Test
    fun blankActionUrl_isIgnored() {
        val n = notif(kind = "system", actionUrl = "   ", targetType = "assignment", targetId = "a_1")
        assertEquals(NotificationAction.OpenAssignment("a_1"), NotificationDeepLinkResolver.resolve(n, Role.TEACHER))
    }

    // ---- 4. targetType/targetId fallback ----

    @Test
    fun targetAssignment_roleSplit() {
        val n = notif(kind = "assignment", targetType = "ASSIGNMENT", targetId = "a_1")
        assertEquals(NotificationAction.OpenAssignment("a_1"), NotificationDeepLinkResolver.resolve(n, Role.TEACHER))
        assertEquals(NotificationAction.OpenChildHomework("a_1"), NotificationDeepLinkResolver.resolve(n, Role.GUARDIAN))
    }

    @Test
    fun targetAttendance_roleSplit() {
        val n = notif(kind = "attendance", targetType = "attendance", targetId = "x_1")
        assertEquals(NotificationAction.OpenChildAttendance("x_1"), NotificationDeepLinkResolver.resolve(n, Role.STUDENT))
        val teacher = NotificationDeepLinkResolver.resolve(n, Role.TEACHER)
        assertEquals(NotificationAction.OpenAttendance("x_1", LocalDate.now().toString()), teacher)
    }

    @Test
    fun targetTypeWithoutId_fallsToKind() {
        val n = notif(kind = "assignment", targetType = "assignment", targetId = null)
        assertEquals(NotificationAction.None, NotificationDeepLinkResolver.resolve(n, Role.TEACHER))
    }

    // ---- 5. kind fallback ----

    @Test
    fun kindAnnouncement_caseInsensitive() {
        val n = notif(kind = "ANNOUNCEMENT")
        assertTrue(NotificationDeepLinkResolver.resolve(n, null) is NotificationAction.ShowAnnouncement)
    }

    @Test
    fun unknownKind_noTarget_resolvesNone() {
        val n = notif(kind = "SYSTEM")
        assertEquals(NotificationAction.None, NotificationDeepLinkResolver.resolve(n, Role.GUARDIAN))
    }
}
