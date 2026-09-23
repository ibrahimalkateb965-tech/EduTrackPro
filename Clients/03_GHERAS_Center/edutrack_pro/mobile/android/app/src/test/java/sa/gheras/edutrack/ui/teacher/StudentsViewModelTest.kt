package sa.gheras.edutrack.ui.teacher

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
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import sa.gheras.edutrack.data.db.GherasDatabase
import sa.gheras.edutrack.data.entity.RoomEntity
import sa.gheras.edutrack.data.entity.StudentEntity
import sa.gheras.edutrack.data.local.session.FakeSessionStore
import sa.gheras.edutrack.data.local.session.Role
import sa.gheras.edutrack.data.local.session.SessionUser
import sa.gheras.edutrack.data.remote.dto.ProfileDto
import sa.gheras.edutrack.data.remote.dto.ProfileScopeDto
import sa.gheras.edutrack.data.remote.dto.ProfileUserDto
import sa.gheras.edutrack.data.repo.StudentsRepository
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StudentsViewModelTest {

    private lateinit var db: GherasDatabase
    private lateinit var session: FakeSessionStore
    private val now: Instant = Instant.parse("2026-09-23T08:00:00Z")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, GherasDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        session = FakeSessionStore()
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun room(id: String) = RoomEntity(
        id = id, branchId = "b1", name = "حلقة $id", groupName = "g",
        createdAt = now, updatedAt = now
    )

    private fun student(id: String, roomId: String?) = StudentEntity(
        id = id, branchId = "b1", name = "طالب $id", nationalId = null, birthDate = null,
        nationality = null, difficultyNotes = null, childNotes = null, fatherName = null,
        fatherPhone = null, motherName = null, motherPhone = null, guardianPhone = null,
        guardianRelation = null, pickupType = null, pickupName = null, pickupRelation = null,
        pickupPhone = null, previousSchool = null, previousLevel = null, educationNotes = null,
        roomId = roomId, groupName = null, createdAt = now, updatedAt = now
    )

    private fun loginAs(role: Role, roomIds: List<String>) {
        session.user = SessionUser(id = "u1", username = "t1", role = role, name = "معلم")
        session.profile = ProfileDto(
            user = ProfileUserDto(id = "u1", username = "t1", role = role.name.lowercase(), name = "معلم"),
            scope = ProfileScopeDto(roomIds = roomIds)
        )
    }

    private fun seed() = runBlocking {
        db.roomDao().upsertAll(listOf(room("r1"), room("r2"), room("r3")))
        db.studentDao().upsertAll(
            listOf(student("s1", "r1"), student("s2", "r2"), student("s3", "r3"), student("s4", null))
        )
    }

    private fun loadedState(vm: StudentsViewModel): StudentsUiState = runBlocking {
        withTimeout(5_000) { vm.uiState.first { !it.isLoading } }
    }

    private fun newVm() = StudentsViewModel(
        StudentsRepository(db.studentDao()), db.roomDao(), session, pullSync = null
    )

    @Test
    fun `teacher sees only students and rooms in assigned scope`() {
        seed()
        loginAs(Role.TEACHER, listOf("r1", "r2"))

        val state = loadedState(newVm())

        assertTrue(state.isScopedToAssigned)
        assertEquals(setOf("r1", "r2"), state.rooms.map { it.id }.toSet())
        assertEquals(setOf("s1", "s2"), state.students.map { it.student.id }.toSet())
        assertEquals(null, state.selectedRoomId)
    }

    @Test
    fun `teacher with a single circle has it auto-selected`() {
        seed()
        loginAs(Role.TEACHER, listOf("r3"))

        val state = loadedState(newVm())

        assertEquals("r3", state.selectedRoomId)
        assertEquals(listOf("s3"), state.students.map { it.student.id })
        assertEquals(listOf("r3"), state.rooms.map { it.id })
    }

    @Test
    fun `teacher cannot widen scope by selecting an unassigned room`() {
        seed()
        loginAs(Role.TEACHER, listOf("r1"))
        val vm = newVm()

        vm.selectRoom("r2")

        assertTrue(loadedState(vm).students.isEmpty())
    }

    @Test
    fun `no scope filtering when teacher has no assigned rooms or role is not teacher`() {
        assertFalse(
            StudentsViewModel.scopeRoster(Role.TEACHER, emptyList(), emptyList(), emptyList()).isScoped
        )
        val rooms = listOf(room("r1"), room("r2"))
        val students = listOf(student("s1", "r1"), student("s2", "r2"))
        val guardian = StudentsViewModel.scopeRoster(Role.GUARDIAN, listOf("r1"), rooms, students)
        assertFalse(guardian.isScoped)
        assertEquals(2, guardian.students.size)
    }
}
