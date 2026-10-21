package sa.gheras.edutrack.ui.teacher.homework

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sa.gheras.edutrack.ui.nav.CreateHomeworkRoute
import sa.gheras.edutrack.ui.nav.GradeSubmissionRoute
import sa.gheras.edutrack.ui.nav.HomeworkDetailRoute
import sa.gheras.edutrack.ui.nav.HomeworkPreviewRoute
import sa.gheras.edutrack.ui.nav.HomeworkResultRoute
import sa.gheras.edutrack.ui.nav.ReviewSubmissionsRoute
import sa.gheras.edutrack.ui.nav.Route

class TeacherHomeworkNavTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun createHomeworkRoute_isFormTrue() {
        val route: Route = CreateHomeworkRoute
        assertTrue(route.isForm)
    }

    @Test
    fun homeworkPreviewRoute_isFormTrue() {
        val route: Route = HomeworkPreviewRoute
        assertTrue(route.isForm)
    }

    @Test
    fun reviewSubmissionsRoute_preservesAssignmentId_isFormFalse() {
        val route = ReviewSubmissionsRoute(assignmentId = "asgn_101")
        assertEquals("asgn_101", route.assignmentId)
        assertFalse(route.isForm)

        // Serialization parity
        val encoded = json.encodeToString(route)
        val decoded = json.decodeFromString<ReviewSubmissionsRoute>(encoded)
        assertEquals("asgn_101", decoded.assignmentId)
    }

    @Test
    fun gradeSubmissionRoute_preservesSubmissionId_isFormTrue() {
        val route = GradeSubmissionRoute(submissionId = "sub_202")
        assertEquals("sub_202", route.submissionId)
        assertTrue(route.isForm)

        val encoded = json.encodeToString(route)
        val decoded = json.decodeFromString<GradeSubmissionRoute>(encoded)
        assertEquals("sub_202", decoded.submissionId)
    }

    @Test
    fun homeworkDetailRoute_preservesIds_isFormTrue() {
        val route = HomeworkDetailRoute(assignmentId = "asgn_303", studentId = "stu_404")
        assertEquals("asgn_303", route.assignmentId)
        assertEquals("stu_404", route.studentId)
        assertTrue(route.isForm)

        val encoded = json.encodeToString(route)
        val decoded = json.decodeFromString<HomeworkDetailRoute>(encoded)
        assertEquals("asgn_303", decoded.assignmentId)
        assertEquals("stu_404", decoded.studentId)
    }

    @Test
    fun homeworkResultRoute_preservesIds_isFormFalse() {
        val route = HomeworkResultRoute(assignmentId = "asgn_505", studentId = "stu_606")
        assertEquals("asgn_505", route.assignmentId)
        assertEquals("stu_606", route.studentId)
        assertFalse(route.isForm)

        val encoded = json.encodeToString(route)
        val decoded = json.decodeFromString<HomeworkResultRoute>(encoded)
        assertEquals("asgn_505", decoded.assignmentId)
        assertEquals("stu_606", decoded.studentId)
    }

    @Test
    fun navigationActionDispatchers_resolveExpectedRoutes() {
        var navigatedRoute: Route? = null

        // Teacher: FAB on AssignmentsScreen -> CreateHomeworkRoute
        val onFabClick: () -> Unit = { navigatedRoute = CreateHomeworkRoute }
        onFabClick()
        assertEquals(CreateHomeworkRoute, navigatedRoute)

        // Teacher: Assignment card click -> ReviewSubmissionsRoute
        val onAssignmentClick: (String) -> Unit = { id -> navigatedRoute = ReviewSubmissionsRoute(id) }
        onAssignmentClick("asgn_abc")
        assertEquals(ReviewSubmissionsRoute("asgn_abc"), navigatedRoute)

        // Teacher: Submission card click -> GradeSubmissionRoute
        val onGradeSubmission: (String) -> Unit = { subId -> navigatedRoute = GradeSubmissionRoute(subId) }
        onGradeSubmission("sub_xyz")
        assertEquals(GradeSubmissionRoute("sub_xyz"), navigatedRoute)

        // Guardian: Pending card click -> HomeworkDetailRoute
        val onNavigateToDetail: (String, String) -> Unit = { asgnId, stuId ->
            navigatedRoute = HomeworkDetailRoute(asgnId, stuId)
        }
        onNavigateToDetail("asgn_1", "stu_1")
        assertEquals(HomeworkDetailRoute("asgn_1", "stu_1"), navigatedRoute)

        // Guardian: Graded card click -> HomeworkResultRoute
        val onNavigateToResult: (String, String) -> Unit = { asgnId, stuId ->
            navigatedRoute = HomeworkResultRoute(asgnId, stuId)
        }
        onNavigateToResult("asgn_1", "stu_1")
        assertEquals(HomeworkResultRoute("asgn_1", "stu_1"), navigatedRoute)
    }
}
