package sa.gheras.edutrack.ui.notifications

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import sa.gheras.edutrack.data.db.GherasDatabase
import sa.gheras.edutrack.data.entity.NotificationEntity
import sa.gheras.edutrack.data.local.session.FakeSessionStore
import sa.gheras.edutrack.data.local.session.Role
import sa.gheras.edutrack.data.local.session.SessionUser
import sa.gheras.edutrack.data.repo.NotificationsRepository
import sa.gheras.edutrack.sync.Outbox
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NotificationsViewModelTest {

    private lateinit var db: GherasDatabase
    private lateinit var session: FakeSessionStore
    private lateinit var notifsRepo: NotificationsRepository
    private val now: Instant = Instant.now()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, GherasDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val outbox = Outbox(context, db)
        session = FakeSessionStore()
        session.user = SessionUser(id = "u1", username = "teacher1", role = Role.TEACHER, name = "معلم 1")
        notifsRepo = NotificationsRepository(db.notificationDao(), outbox)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun notif(
        id: String,
        kind: String,
        title: String,
        body: String,
        readAt: Instant? = null
    ) = NotificationEntity(
        id = id,
        branchId = "b1",
        userId = "u1",
        kind = kind,
        title = title,
        body = body,
        readAt = readAt,
        sentAt = now,
        createdAt = now,
        updatedAt = now
    )

    private fun seed() = runBlocking {
        db.notificationDao().upsertAll(
            listOf(
                notif("n1", "attendance_alert", "غياب طالب", "غاب أحمد علي اليوم", readAt = null),
                notif("n2", "assignment_new", "تكليف جديد", "تم إسناد حفظ سورة الملك", readAt = null),
                notif("n3", "general_broadcast", "تعميم إداري", "اجتماع الكادر التعليمي غداً", readAt = now)
            )
        )
    }

    private fun createVm() = NotificationsViewModel(
        sessionStore = session,
        notificationsRepository = notifsRepo,
        roomDao = db.roomDao(),
        pullSync = null
    )

    @Test
    fun `loads notifications and calculates unread count accurately`() = runBlocking {
        seed()
        val vm = createVm()
        val state = vm.uiState.first { !it.isLoading && it.notifications.isNotEmpty() }

        assertEquals(3, state.notifications.size)
        assertEquals(2, state.unreadCount)
        assertTrue(state.isTeacher)
    }

    @Test
    fun `filters notifications by kind and read status`() = runBlocking {
        seed()
        val vm = createVm()
        vm.uiState.first { !it.isLoading && it.notifications.isNotEmpty() }

        // Filter UNREAD
        vm.setFilter(NotificationFilter.UNREAD)
        var state = vm.uiState.first { it.filter == NotificationFilter.UNREAD }
        assertEquals(2, state.notifications.size)
        assertTrue(state.notifications.all { it.readAt == null })

        // Filter ATTENDANCE
        vm.setFilter(NotificationFilter.ATTENDANCE)
        state = vm.uiState.first { it.filter == NotificationFilter.ATTENDANCE }
        assertEquals(1, state.notifications.size)
        assertEquals("n1", state.notifications.first().id)

        // Filter ASSIGNMENTS
        vm.setFilter(NotificationFilter.ASSIGNMENTS)
        state = vm.uiState.first { it.filter == NotificationFilter.ASSIGNMENTS }
        assertEquals(1, state.notifications.size)
        assertEquals("n2", state.notifications.first().id)

        // Filter GENERAL
        vm.setFilter(NotificationFilter.GENERAL)
        state = vm.uiState.first { it.filter == NotificationFilter.GENERAL }
        assertEquals(1, state.notifications.size)
        assertEquals("n3", state.notifications.first().id)
    }

    @Test
    fun `search filters notifications by query`() = runBlocking {
        seed()
        val vm = createVm()
        vm.uiState.first { !it.isLoading && it.notifications.isNotEmpty() }

        vm.setSearchQuery("الملك")
        val state = vm.uiState.first { it.searchQuery == "الملك" }
        assertEquals(1, state.notifications.size)
        assertEquals("n2", state.notifications.first().id)
    }

    @Test
    fun `markAsRead updates unread counter and database record`() = runBlocking {
        seed()
        val vm = createVm()
        vm.uiState.first { !it.isLoading && it.unreadCount == 2 }

        vm.markAsRead("n1")
        val state = vm.uiState.first { it.unreadCount == 1 }

        assertEquals(1, state.unreadCount)
        val updated = db.notificationDao().getById("n1")
        assertNotNull(updated?.readAt)
    }

    @Test
    fun `markAllAsRead marks all user notifications as read`() = runBlocking {
        seed()
        val vm = createVm()
        vm.uiState.first { !it.isLoading && it.unreadCount == 2 }

        vm.markAllAsRead()
        val state = vm.uiState.first { it.unreadCount == 0 }

        assertEquals(0, state.unreadCount)
        val unreadInDb = db.notificationDao().countUnreadByUser("u1").first()
        assertEquals(0, unreadInDb)
    }

    @Test
    fun `dismissNotification and undoDismiss restores notification`() = runBlocking {
        seed()
        val vm = createVm()
        vm.uiState.first { !it.isLoading && it.notifications.size == 3 }

        val n1 = db.notificationDao().getById("n1")!!
        vm.dismissNotification(n1)

        val dismissedState = vm.uiState.first { it.pendingUndoNotification?.id == "n1" }
        assertEquals("n1", dismissedState.pendingUndoNotification?.id)

        // Undo
        vm.undoDismiss()
        val restoredState = vm.uiState.first { it.pendingUndoNotification == null }
        assertNull(restoredState.pendingUndoNotification)
        val restoredInDb = db.notificationDao().getById("n1")
        assertNull(restoredInDb?.deletedAt)
    }
}
