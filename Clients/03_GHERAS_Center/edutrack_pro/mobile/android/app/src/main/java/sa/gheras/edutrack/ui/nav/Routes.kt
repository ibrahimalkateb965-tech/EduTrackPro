package sa.gheras.edutrack.ui.nav

import kotlinx.serialization.Serializable

sealed interface Route {
    val isForm: Boolean get() = false
}

// ---- Auth Graph ----
@Serializable
data class LoginRoute(val mode: String = "Fresh") : Route

@Serializable
data object ChangePasswordRoute : Route {
    override val isForm: Boolean get() = true
}

@Serializable
data object AccountRoute : Route

// ---- Teacher Graph ----
@Serializable
data object TeacherHomeRoute : Route

@Serializable
data object TeacherStudentsRoute : Route

@Serializable
data class StudentDetailRoute(val studentId: String) : Route

@Serializable
data class AttendanceSheetRoute(val roomId: String, val date: String) : Route {
    override val isForm: Boolean get() = true
}

@Serializable
data class EvaluationSheetRoute(val roomId: String, val date: String, val subject: String = "") : Route {
    override val isForm: Boolean get() = true
}

@Serializable
data class LessonLogRoute(val scheduleId: String, val date: String) : Route {
    override val isForm: Boolean get() = true
}

@Serializable
data object TeacherAssignmentsRoute : Route

@Serializable
data class AssignmentDetailRoute(val assignmentId: String) : Route

@Serializable
data object NotificationsRoute : Route

// ---- Guardian Graph ----
@Serializable
data object GuardianHomeRoute : Route

@Serializable
data class ChildAttendanceRoute(val studentId: String) : Route

@Serializable
data class ChildEvaluationsRoute(val studentId: String) : Route

@Serializable
data class ChildHomeworkRoute(val studentId: String) : Route

@Serializable
data class ChildLessonsRoute(val studentId: String) : Route

@Serializable
data class ChildSkillsRoute(val studentId: String) : Route

@Serializable
data class FeesRoute(val studentId: String) : Route
