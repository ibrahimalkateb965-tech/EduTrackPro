package sa.gheras.edutrack.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import sa.gheras.edutrack.data.local.session.Role

data class NavItem(
    val label: String,
    val icon: ImageVector,
    val route: Route
)

@Composable
fun AppBottomNavBar(
    role: Role,
    currentRoute: String?,
    onNavigate: (Route) -> Unit,
    modifier: Modifier = Modifier
) {
    val items = when (role) {
        Role.TEACHER -> listOf(
            NavItem("الرئيسية", Icons.Default.Home, TeacherHomeRoute),
            NavItem("الطلاب", Icons.Default.School, TeacherStudentsRoute),
            NavItem("الواجبات", Icons.Default.Assignment, TeacherAssignmentsRoute),
            NavItem("التنبيهات", Icons.Default.Notifications, NotificationsRoute),
            NavItem("حسابي", Icons.Default.Person, AccountRoute)
        )
        Role.GUARDIAN -> listOf(
            NavItem("الرئيسية", Icons.Default.Home, GuardianHomeRoute),
            NavItem("الرسوم", Icons.Default.CreditCard, FeesRoute("")),
            NavItem("التنبيهات", Icons.Default.Notifications, NotificationsRoute),
            NavItem("حسابي", Icons.Default.Person, AccountRoute)
        )
        Role.STUDENT -> listOf(
            NavItem("الرئيسية", Icons.Default.Home, GuardianHomeRoute),
            NavItem("التنبيهات", Icons.Default.Notifications, NotificationsRoute),
            NavItem("حسابي", Icons.Default.Person, AccountRoute)
        )
    }

    NavigationBar(modifier = modifier) {
        items.forEach { item ->
            val routeClassName = item.route::class.qualifiedName ?: ""
            val isSelected = currentRoute?.contains(item.route::class.simpleName ?: "") == true

            NavigationBarItem(
                selected = isSelected,
                onClick = { onNavigate(item.route) },
                icon = { Icon(item.icon, contentDescription = item.label) },
                label = { Text(item.label) }
            )
        }
    }
}
