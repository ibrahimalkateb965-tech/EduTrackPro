package sa.gheras.edutrack.ui.nav

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.toRoute
import kotlinx.coroutines.launch
import sa.gheras.edutrack.data.local.session.Role
import sa.gheras.edutrack.data.repo.SessionState
import sa.gheras.edutrack.di.AppContainer
import sa.gheras.edutrack.di.viewModelFactory
import sa.gheras.edutrack.ui.account.AccountScreen
import sa.gheras.edutrack.ui.account.AccountViewModel
import sa.gheras.edutrack.ui.account.ChangePasswordScreen
import sa.gheras.edutrack.ui.account.ChangePasswordViewModel
import sa.gheras.edutrack.ui.guardian.ChildAttendanceScreen
import sa.gheras.edutrack.ui.guardian.ChildAttendanceViewModel
import sa.gheras.edutrack.ui.guardian.ChildEvaluationsScreen
import sa.gheras.edutrack.ui.guardian.ChildEvaluationsViewModel
import sa.gheras.edutrack.ui.guardian.ChildHomeworkScreen
import sa.gheras.edutrack.ui.guardian.ChildHomeworkViewModel
import sa.gheras.edutrack.ui.guardian.homework.HomeworkDetailScreen
import sa.gheras.edutrack.ui.guardian.homework.HomeworkResultScreen
import sa.gheras.edutrack.ui.guardian.ChildLessonsScreen
import sa.gheras.edutrack.ui.guardian.ChildLessonsViewModel
import sa.gheras.edutrack.ui.guardian.ChildSkillsScreen
import sa.gheras.edutrack.ui.guardian.ChildSkillsViewModel
import sa.gheras.edutrack.ui.guardian.FeesScreen
import sa.gheras.edutrack.ui.guardian.FeesViewModel
import sa.gheras.edutrack.ui.guardian.GuardianHomeScreen
import sa.gheras.edutrack.ui.guardian.GuardianHomeViewModel
import sa.gheras.edutrack.ui.guardian.pickup.PickupEarlyLeaveScreen
import sa.gheras.edutrack.ui.guardian.pickup.PickupRequestScreen
import sa.gheras.edutrack.ui.guardian.pickup.PickupTrackingScreen
import sa.gheras.edutrack.ui.guardian.pickup.PickupViewModel
import sa.gheras.edutrack.ui.login.LoginScreen
import sa.gheras.edutrack.ui.login.LoginViewModel
import sa.gheras.edutrack.ui.notifications.NotificationAction
import sa.gheras.edutrack.ui.notifications.NotificationsScreen
import sa.gheras.edutrack.ui.notifications.NotificationsViewModel
import sa.gheras.edutrack.ui.teacher.AssignmentDetailScreen
import sa.gheras.edutrack.ui.teacher.AssignmentDetailViewModel
import sa.gheras.edutrack.ui.teacher.AssignmentsScreen
import sa.gheras.edutrack.ui.teacher.AssignmentsViewModel
import sa.gheras.edutrack.ui.teacher.homework.CreateHomeworkScreen
import sa.gheras.edutrack.ui.teacher.homework.HomeworkPreviewScreen
import sa.gheras.edutrack.ui.teacher.homework.ReviewSubmissionsScreen
import sa.gheras.edutrack.ui.teacher.homework.GradeSubmissionScreen
import sa.gheras.edutrack.ui.teacher.homework.TeacherHomeworkViewModel
import sa.gheras.edutrack.ui.teacher.AttendanceSheetScreen
import sa.gheras.edutrack.ui.teacher.AttendanceSheetViewModel
import sa.gheras.edutrack.ui.teacher.EvaluationSheetScreen
import sa.gheras.edutrack.ui.teacher.EvaluationSheetViewModel
import sa.gheras.edutrack.ui.teacher.LessonLogScreen
import sa.gheras.edutrack.ui.teacher.LessonLogViewModel
import sa.gheras.edutrack.ui.teacher.StudentDetailScreen
import sa.gheras.edutrack.ui.teacher.StudentDetailViewModel
import sa.gheras.edutrack.ui.teacher.StudentsScreen
import sa.gheras.edutrack.ui.teacher.StudentsViewModel
import sa.gheras.edutrack.ui.teacher.TeacherHomeScreen
import sa.gheras.edutrack.ui.teacher.TeacherHomeViewModel
import sa.gheras.edutrack.ui.supervisor.pickup.SupervisorGateViewModel
import sa.gheras.edutrack.ui.supervisor.pickup.SupervisorHandoverScreen
import sa.gheras.edutrack.ui.supervisor.pickup.SupervisorPickupScreen
import sa.gheras.edutrack.ui.teacher.pickup.TeacherChildPrepScreen
import sa.gheras.edutrack.ui.teacher.pickup.TeacherPickupQueueScreen
import sa.gheras.edutrack.ui.teacher.pickup.TeacherPickupViewModel

@Composable
fun RootNavHost(
    container: AppContainer,
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    val sessionState by container.sessionRepository.state.collectAsState()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination?.route

    // React to session state changes
    LaunchedEffect(sessionState) {
        when (val state = sessionState) {
            is SessionState.SignedOut -> {
                navController.navigate(LoginRoute("Fresh")) {
                    popUpTo(0) { inclusive = true }
                }
            }
            is SessionState.Active -> {
                // Proactive full sync on cold start / session restore; launched so navigation is not blocked.
                launch { container.pullSync.requestFull() }
                if (state.role == Role.TEACHER) {
                    if (currentDestination?.contains("teacher") != true) {
                        navController.navigate(TeacherHomeRoute) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                } else if (state.role == Role.GUARDIAN || state.role == Role.STUDENT) {
                    if (currentDestination?.contains("guardian") != true) {
                        navController.navigate(GuardianHomeRoute) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                } else if (state.role == Role.SUPERVISOR || state.role == Role.MANAGER) {
                    if (currentDestination?.contains("supervisor") != true && currentDestination?.contains("pickup") != true) {
                        navController.navigate(SupervisorPickupRoute) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                }
            }
            is SessionState.Expired -> {
                // If not currently filling a form, navigate to login
                val isForm = currentDestination?.contains("Sheet") == true || currentDestination?.contains("Log") == true
                if (!isForm && currentDestination?.contains("Login") != true) {
                    navController.navigate(LoginRoute("Expired"))
                }
            }
        }
    }

    val activeRole = (sessionState as? SessionState.Active)?.role
    val isBottomBarVisible = activeRole != null && (
        currentDestination?.endsWith("HomeRoute") == true ||
        currentDestination?.endsWith("StudentsRoute") == true ||
        currentDestination?.endsWith("AssignmentsRoute") == true ||
        currentDestination?.endsWith("NotificationsRoute") == true ||
        currentDestination?.endsWith("AccountRoute") == true ||
        (currentDestination?.contains("FeesRoute") == true && activeRole != Role.STUDENT)
    )

    Scaffold(
        bottomBar = {
            if (isBottomBarVisible && activeRole != null) {
                AppBottomNavBar(
                    role = activeRole,
                    currentRoute = currentDestination,
                    onNavigate = { route ->
                        navController.navigate(route) {
                            popUpTo(navController.graph.startDestinationId) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Persistent Expiry Banner if expired and not on login screen
            AnimatedVisibility(visible = sessionState is SessionState.Expired && currentDestination?.contains("Login") != true) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.error)
                        .clickable { navController.navigate(LoginRoute("Expired")) }
                        .padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onError,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "انتهت صلاحية الجلسة — اضغط هنا لتسجيل الدخول",
                            color = MaterialTheme.colorScheme.onError,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            NavHost(
                navController = navController,
                startDestination = LoginRoute(),
                modifier = Modifier.weight(1f)
            ) {
                // ---- Auth Graph ----
                composable<LoginRoute> { backStackEntry ->
                    val route: LoginRoute = backStackEntry.toRoute()
                    val vm: LoginViewModel = viewModel(
                        factory = viewModelFactory {
                            LoginViewModel(container.sessionRepository, container.outboxRepository, route.mode)
                        }
                    )
                    LoginScreen(
                        viewModel = vm,
                        onLoginSuccess = {
                            val user = container.sessionStore.user
                            if (user?.role == Role.TEACHER) {
                                navController.navigate(TeacherHomeRoute) { popUpTo(0) { inclusive = true } }
                            } else if (user?.role == Role.SUPERVISOR || user?.role == Role.MANAGER) {
                                navController.navigate(SupervisorPickupRoute) { popUpTo(0) { inclusive = true } }
                            } else {
                                navController.navigate(GuardianHomeRoute) { popUpTo(0) { inclusive = true } }
                            }
                        }
                    )
                }

                composable<ChangePasswordRoute> {
                    val vm: ChangePasswordViewModel = viewModel(
                        factory = viewModelFactory {
                            ChangePasswordViewModel(container.sessionRepository)
                        }
                    )
                    ChangePasswordScreen(
                        viewModel = vm,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }

                composable<AccountRoute> {
                    val vm: AccountViewModel = viewModel(
                        factory = viewModelFactory {
                            AccountViewModel(
                                container.sessionStore,
                                container.sessionRepository,
                                container.outboxRepository,
                                container.outbox
                            )
                        }
                    )
                    AccountScreen(
                        viewModel = vm,
                        onChangePasswordClick = { navController.navigate(ChangePasswordRoute) },
                        onLoggedOut = {
                            navController.navigate(LoginRoute("Fresh")) {
                                popUpTo(0) { inclusive = true }
                            }
                        }
                    )
                }

                composable<NotificationsRoute> {
                    val vm: NotificationsViewModel = viewModel(
                        factory = viewModelFactory {
                            NotificationsViewModel(
                                sessionStore = container.sessionStore,
                                notificationsRepository = container.notificationsRepository,
                                roomDao = container.database.roomDao(),
                                pullSync = container.pullSync
                            )
                        }
                    )
                    NotificationsScreen(
                        viewModel = vm,
                        onAction = { action ->
                            when (action) {
                                is NotificationAction.OpenAssignment -> {
                                    if (activeRole == Role.TEACHER) {
                                        navController.navigate(ReviewSubmissionsRoute(action.assignmentId))
                                    } else {
                                        navController.navigate(AssignmentDetailRoute(action.assignmentId))
                                    }
                                }
                                is NotificationAction.OpenAttendance -> {
                                    navController.navigate(AttendanceSheetRoute(action.roomId, action.date))
                                }
                                is NotificationAction.OpenChildHomework -> {
                                    navController.navigate(ChildHomeworkRoute(action.studentId))
                                }
                                is NotificationAction.OpenChildAttendance -> {
                                    navController.navigate(ChildAttendanceRoute(action.studentId))
                                }
                                is NotificationAction.OpenExternalUrl -> {
                                    try {
                                        val intent = android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse(action.url)
                                        )
                                        navController.context.startActivity(intent)
                                    } catch (_: Exception) {}
                                }
                                else -> {}
                            }
                        }
                    )
                }

                // ---- Teacher Graph ----
                composable<TeacherHomeRoute> {
                    val vm: TeacherHomeViewModel = viewModel(
                        factory = viewModelFactory {
                            TeacherHomeViewModel(
                                container.scheduleRepository,
                                container.database.roomDao(),
                                container.attendanceRepository,
                                container.evaluationsRepository,
                                container.lessonLogsRepository,
                                container.outboxRepository,
                                container.pullSync
                            )
                        }
                    )
                    TeacherHomeScreen(
                        viewModel = vm,
                        onAttendanceClick = { roomId, date ->
                            navController.navigate(AttendanceSheetRoute(roomId, date))
                        },
                        onEvaluationClick = { roomId, date, subject ->
                            navController.navigate(EvaluationSheetRoute(roomId, date, subject))
                        },
                        onLessonLogClick = { scheduleId, date ->
                            navController.navigate(LessonLogRoute(scheduleId, date))
                        },
                        onPickupQueueClick = {
                            navController.navigate(TeacherPickupQueueRoute)
                        }
                    )
                }

                composable<TeacherStudentsRoute> {
                    val vm: StudentsViewModel = viewModel(
                        factory = viewModelFactory {
                            StudentsViewModel(
                                container.studentsRepository,
                                container.database.roomDao(),
                                container.sessionStore,
                                container.pullSync
                            )
                        }
                    )
                    StudentsScreen(
                        viewModel = vm,
                        onStudentClick = { studentId ->
                            navController.navigate(StudentDetailRoute(studentId))
                        }
                    )
                }

                composable<StudentDetailRoute> { backStackEntry ->
                    val route: StudentDetailRoute = backStackEntry.toRoute()
                    val vm: StudentDetailViewModel = viewModel(
                        factory = viewModelFactory {
                            StudentDetailViewModel(
                                route.studentId,
                                container.studentsRepository,
                                container.database.roomDao(),
                                container.attendanceRepository,
                                container.evaluationsRepository,
                                container.database.skillProgressDao()
                            )
                        }
                    )
                    StudentDetailScreen(
                        viewModel = vm,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }

                composable<AttendanceSheetRoute> { backStackEntry ->
                    val route: AttendanceSheetRoute = backStackEntry.toRoute()
                    val vm: AttendanceSheetViewModel = viewModel(
                        factory = viewModelFactory {
                            AttendanceSheetViewModel(
                                route.roomId,
                                route.date,
                                container.studentsRepository,
                                container.database.roomDao(),
                                container.attendanceRepository
                            )
                        }
                    )
                    AttendanceSheetScreen(
                        viewModel = vm,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }

                composable<EvaluationSheetRoute> { backStackEntry ->
                    val route: EvaluationSheetRoute = backStackEntry.toRoute()
                    val vm: EvaluationSheetViewModel = viewModel(
                        factory = viewModelFactory {
                            EvaluationSheetViewModel(
                                route.roomId,
                                route.date,
                                route.subject,
                                container.studentsRepository,
                                container.database.roomDao(),
                                container.scheduleRepository,
                                container.evaluationsRepository
                            )
                        }
                    )
                    EvaluationSheetScreen(
                        viewModel = vm,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }

                composable<LessonLogRoute> { backStackEntry ->
                    val route: LessonLogRoute = backStackEntry.toRoute()
                    val vm: LessonLogViewModel = viewModel(
                        factory = viewModelFactory {
                            LessonLogViewModel(
                                route.scheduleId,
                                route.date,
                                container.scheduleRepository,
                                container.database.roomDao(),
                                container.lessonLogsRepository
                            )
                        }
                    )
                    LessonLogScreen(
                        viewModel = vm,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }

                composable<TeacherAssignmentsRoute> {
                    val vm: AssignmentsViewModel = viewModel(
                        factory = viewModelFactory {
                            AssignmentsViewModel(
                                container.assignmentsRepository,
                                container.database.roomDao(),
                                container.database.assignmentStudentDao(),
                                container.studentsRepository,
                                container.database.submissionDao(),
                                container.sessionStore,
                                container.pullSync
                            )
                        }
                    )
                    AssignmentsScreen(
                        viewModel = vm,
                        onAssignmentClick = { assignmentId ->
                            navController.navigate(ReviewSubmissionsRoute(assignmentId))
                        },
                        onCreateClick = {
                            navController.navigate(CreateHomeworkRoute)
                        }
                    )
                }

                composable<AssignmentDetailRoute> { backStackEntry ->
                    val route: AssignmentDetailRoute = backStackEntry.toRoute()
                    val vm: AssignmentDetailViewModel = viewModel(
                        factory = viewModelFactory {
                            AssignmentDetailViewModel(
                                route.assignmentId,
                                container.assignmentsRepository,
                                container.database.assignmentStudentDao(),
                                container.studentsRepository,
                                container.database.roomDao(),
                                container.pullSync
                            )
                        }
                    )
                    AssignmentDetailScreen(
                        viewModel = vm,
                        onNavigateBack = { navController.popBackStack() },
                        onGradeSubmissionClick = { submissionId ->
                            navController.navigate(GradeSubmissionRoute(submissionId))
                        }
                    )
                }

                composable<CreateHomeworkRoute> { backStackEntry ->
                    val vm: TeacherHomeworkViewModel = viewModel(
                        backStackEntry,
                        factory = viewModelFactory {
                            TeacherHomeworkViewModel(
                                container.assignmentsRepository,
                                container.studentsRepository,
                                container.database.roomDao(),
                                container.sessionStore
                            )
                        }
                    )
                    CreateHomeworkScreen(
                        viewModel = vm,
                        onNavigateBack = { navController.popBackStack() },
                        onNavigateToPreview = { navController.navigate(HomeworkPreviewRoute) }
                    )
                }

                composable<HomeworkPreviewRoute> { backStackEntry ->
                    val parentEntry = remember(backStackEntry) {
                        runCatching { navController.getBackStackEntry<CreateHomeworkRoute>() }.getOrNull()
                    }
                    val vm: TeacherHomeworkViewModel = if (parentEntry != null) {
                        viewModel(
                            parentEntry,
                            factory = viewModelFactory {
                                TeacherHomeworkViewModel(
                                    container.assignmentsRepository,
                                    container.studentsRepository,
                                    container.database.roomDao(),
                                    container.sessionStore
                                )
                            }
                        )
                    } else {
                        viewModel(
                            backStackEntry,
                            factory = viewModelFactory {
                                TeacherHomeworkViewModel(
                                    container.assignmentsRepository,
                                    container.studentsRepository,
                                    container.database.roomDao(),
                                    container.sessionStore
                                )
                            }
                        )
                    }
                    HomeworkPreviewScreen(
                        viewModel = vm,
                        onNavigateBack = { navController.popBackStack() },
                        onPublishedSuccess = {
                            navController.popBackStack<CreateHomeworkRoute>(inclusive = true)
                        }
                    )
                }

                composable<ReviewSubmissionsRoute> { backStackEntry ->
                    val route: ReviewSubmissionsRoute = backStackEntry.toRoute()
                    ReviewSubmissionsScreen(
                        assignmentId = route.assignmentId,
                        assignmentsRepository = container.assignmentsRepository,
                        studentsRepository = container.studentsRepository,
                        onNavigateBack = { navController.popBackStack() },
                        onGradeSubmission = { submissionId ->
                            navController.navigate(GradeSubmissionRoute(submissionId))
                        }
                    )
                }

                composable<GradeSubmissionRoute> { backStackEntry ->
                    val route: GradeSubmissionRoute = backStackEntry.toRoute()
                    GradeSubmissionScreen(
                        submissionId = route.submissionId,
                        assignmentsRepository = container.assignmentsRepository,
                        studentsRepository = container.studentsRepository,
                        onNavigateBack = { navController.popBackStack() },
                        onReviewedSuccess = {
                            navController.popBackStack()
                        }
                    )
                }

                composable<TeacherPickupQueueRoute> {
                    val vm: TeacherPickupViewModel = viewModel(
                        factory = viewModelFactory {
                            TeacherPickupViewModel(container.pickupRepository)
                        }
                    )
                    TeacherPickupQueueScreen(
                        viewModel = vm,
                        onNavigateBack = { navController.popBackStack() },
                        onPrepClick = { requestId ->
                            navController.navigate(TeacherChildPrepRoute(requestId))
                        }
                    )
                }

                composable<TeacherChildPrepRoute> { backStackEntry ->
                    val route: TeacherChildPrepRoute = backStackEntry.toRoute()
                    val vm: TeacherPickupViewModel = viewModel(
                        factory = viewModelFactory {
                            TeacherPickupViewModel(container.pickupRepository)
                        }
                    )
                    TeacherChildPrepScreen(
                        requestId = route.requestId,
                        viewModel = vm,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }

                // ---- Guardian Graph ----
                composable<GuardianHomeRoute> {
                    val vm: GuardianHomeViewModel = viewModel(
                        factory = viewModelFactory {
                            GuardianHomeViewModel(
                                container.studentsRepository,
                                container.attendanceRepository,
                                container.evaluationsRepository,
                                container.assignmentsRepository,
                                container.feesRepository,
                                container.pullSync
                            )
                        }
                    )
                    GuardianHomeScreen(
                        viewModel = vm,
                        onAttendanceClick = { studentId -> navController.navigate(ChildAttendanceRoute(studentId)) },
                        onEvaluationsClick = { studentId -> navController.navigate(ChildEvaluationsRoute(studentId)) },
                        onHomeworkClick = { studentId -> navController.navigate(ChildHomeworkRoute(studentId)) },
                        onLessonsClick = { studentId -> navController.navigate(ChildLessonsRoute(studentId)) },
                        onSkillsClick = { studentId -> navController.navigate(ChildSkillsRoute(studentId)) },
                        onFeesClick = { studentId -> navController.navigate(FeesRoute(studentId)) },
                        onPickupClick = { studentId ->
                            navController.navigate(PickupRequestRoute(studentId))
                        },
                        showFees = activeRole != Role.STUDENT
                    )
                }

                composable<ChildAttendanceRoute> { backStackEntry ->
                    val route: ChildAttendanceRoute = backStackEntry.toRoute()
                    val vm: ChildAttendanceViewModel = viewModel(
                        factory = viewModelFactory {
                            ChildAttendanceViewModel(
                                route.studentId,
                                container.studentsRepository,
                                container.attendanceRepository
                            )
                        }
                    )
                    ChildAttendanceScreen(
                        viewModel = vm,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }

                composable<ChildEvaluationsRoute> { backStackEntry ->
                    val route: ChildEvaluationsRoute = backStackEntry.toRoute()
                    val vm: ChildEvaluationsViewModel = viewModel(
                        factory = viewModelFactory {
                            ChildEvaluationsViewModel(
                                route.studentId,
                                container.studentsRepository,
                                container.evaluationsRepository
                            )
                        }
                    )
                    ChildEvaluationsScreen(
                        viewModel = vm,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }

                composable<ChildHomeworkRoute> { backStackEntry ->
                    val route: ChildHomeworkRoute = backStackEntry.toRoute()
                    val vm: ChildHomeworkViewModel = viewModel(
                        factory = viewModelFactory {
                            ChildHomeworkViewModel(
                                route.studentId,
                                container.studentsRepository,
                                container.assignmentsRepository,
                                container.database.assignmentStudentDao()
                            )
                        }
                    )
                    ChildHomeworkScreen(
                        viewModel = vm,
                        onNavigateBack = { navController.popBackStack() },
                        onNavigateToDetail = { assignmentId ->
                            navController.navigate(HomeworkDetailRoute(assignmentId, route.studentId))
                        },
                        onNavigateToResult = { assignmentId ->
                            navController.navigate(HomeworkResultRoute(assignmentId, route.studentId))
                        }
                    )
                }

                composable<HomeworkDetailRoute> { backStackEntry ->
                    val route: HomeworkDetailRoute = backStackEntry.toRoute()
                    HomeworkDetailScreen(
                        assignmentId = route.assignmentId,
                        studentId = route.studentId,
                        assignmentsRepository = container.assignmentsRepository,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }

                composable<HomeworkResultRoute> { backStackEntry ->
                    val route: HomeworkResultRoute = backStackEntry.toRoute()
                    HomeworkResultScreen(
                        assignmentId = route.assignmentId,
                        studentId = route.studentId,
                        assignmentsRepository = container.assignmentsRepository,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }

                composable<ChildLessonsRoute> { backStackEntry ->
                    val route: ChildLessonsRoute = backStackEntry.toRoute()
                    val vm: ChildLessonsViewModel = viewModel(
                        factory = viewModelFactory {
                            ChildLessonsViewModel(
                                route.studentId,
                                container.studentsRepository,
                                container.database.scheduleDao(),
                                container.database.lessonLogDao()
                            )
                        }
                    )
                    ChildLessonsScreen(
                        viewModel = vm,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }

                composable<ChildSkillsRoute> { backStackEntry ->
                    val route: ChildSkillsRoute = backStackEntry.toRoute()
                    val vm: ChildSkillsViewModel = viewModel(
                        factory = viewModelFactory {
                            ChildSkillsViewModel(
                                route.studentId,
                                container.studentsRepository,
                                container.database.skillProgressDao()
                            )
                        }
                    )
                    ChildSkillsScreen(
                        viewModel = vm,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }

                composable<FeesRoute> { backStackEntry ->
                    val route: FeesRoute = backStackEntry.toRoute()
                    val vm: FeesViewModel = viewModel(
                        factory = viewModelFactory {
                            FeesViewModel(
                                route.studentId.ifBlank { null },
                                container.studentsRepository,
                                container.feesRepository
                            )
                        }
                    )
                    FeesScreen(viewModel = vm)
                }

                // ---- Guardian Pickup Graph ----
                composable<PickupRequestRoute> { backStackEntry ->
                    val route: PickupRequestRoute = backStackEntry.toRoute()
                    val vm: PickupViewModel = viewModel(
                        factory = viewModelFactory {
                            PickupViewModel(
                                route.studentId,
                                container.studentsRepository,
                                container.pickupRepository
                            )
                        }
                    )
                    PickupRequestScreen(
                        viewModel = vm,
                        onNavigateBack = { navController.popBackStack() },
                        onEarlyLeaveClick = { navController.navigate(PickupEarlyLeaveRoute(route.studentId)) },
                        onRequestSuccess = { requestId ->
                            navController.navigate(PickupTrackingRoute(requestId)) {
                                popUpTo<PickupRequestRoute> { inclusive = true }
                            }
                        }
                    )
                }

                composable<PickupTrackingRoute> { backStackEntry ->
                    val route: PickupTrackingRoute = backStackEntry.toRoute()
                    PickupTrackingScreen(
                        requestId = route.requestId,
                        pickupRepository = container.pickupRepository,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }

                composable<PickupEarlyLeaveRoute> { backStackEntry ->
                    val route: PickupEarlyLeaveRoute = backStackEntry.toRoute()
                    val vm: PickupViewModel = viewModel(
                        factory = viewModelFactory {
                            PickupViewModel(
                                route.studentId,
                                container.studentsRepository,
                                container.pickupRepository
                            )
                        }
                    )
                    PickupEarlyLeaveScreen(
                        viewModel = vm,
                        onNavigateBack = { navController.popBackStack() },
                        onRequestSuccess = { requestId ->
                            navController.navigate(PickupTrackingRoute(requestId)) {
                                popUpTo<PickupEarlyLeaveRoute> { inclusive = true }
                            }
                        }
                    )
                }

                // ---- Supervisor Graph ----
                composable<SupervisorPickupRoute> {
                    val vm: SupervisorGateViewModel = viewModel(
                        factory = viewModelFactory {
                            SupervisorGateViewModel(container.pickupRepository)
                        }
                    )
                    SupervisorPickupScreen(
                        viewModel = vm,
                        onNavigateToHandover = { requestId ->
                            navController.navigate(SupervisorHandoverRoute(requestId))
                        }
                    )
                }

                composable<SupervisorHandoverRoute> { backStackEntry ->
                    val route: SupervisorHandoverRoute = backStackEntry.toRoute()
                    val vm: SupervisorGateViewModel = viewModel(
                        factory = viewModelFactory {
                            SupervisorGateViewModel(container.pickupRepository)
                        }
                    )
                    SupervisorHandoverScreen(
                        requestId = route.requestId,
                        viewModel = vm,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
            }
        }
    }
}
