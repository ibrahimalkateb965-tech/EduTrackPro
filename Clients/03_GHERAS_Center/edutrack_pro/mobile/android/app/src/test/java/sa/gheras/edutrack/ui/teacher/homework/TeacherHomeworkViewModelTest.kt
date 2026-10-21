package sa.gheras.edutrack.ui.teacher.homework

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import sa.gheras.edutrack.data.db.GherasDatabase
import sa.gheras.edutrack.data.entity.RoomEntity
import sa.gheras.edutrack.data.entity.StudentEntity
import sa.gheras.edutrack.data.local.session.FakeSessionStore
import sa.gheras.edutrack.data.remote.fakes.FakeMeApi
import sa.gheras.edutrack.data.repo.AssignmentsRepository
import sa.gheras.edutrack.data.repo.StudentsRepository
import java.time.Instant
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TeacherHomeworkViewModelTest {

    private lateinit var db: GherasDatabase
    private lateinit var fakeMeApi: FakeMeApi
    private lateinit var fakeSessionStore: FakeSessionStore
    private lateinit var assignmentsRepo: AssignmentsRepository
    private lateinit var studentsRepo: StudentsRepository
    private val now = Instant.parse("2026-10-21T10:00:00Z")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, GherasDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        fakeMeApi = FakeMeApi()
        fakeSessionStore = FakeSessionStore()

        assignmentsRepo = AssignmentsRepository(
            assignmentDao = db.assignmentDao(),
            submissionDao = db.submissionDao(),
            assignmentStudentDao = db.assignmentStudentDao(),
            submissionFileDao = db.submissionFileDao(),
            meApi = fakeMeApi,
            outbox = null
        )
        studentsRepo = StudentsRepository(db.studentDao())

        // Seed room and students
        runBlocking {
            db.roomDao().upsert(
                RoomEntity(
                    id = "room_1",
                    branchId = "b1",
                    name = "حلقة الإمام نافع",
                    groupName = "quran",
                    createdAt = now,
                    updatedAt = now
                )
            )
            db.studentDao().upsertAll(
                listOf(
                    StudentEntity(id = "s1", branchId = "b1", name = "Zaid", gender = "MALE", roomId = "room_1", createdAt = now, updatedAt = now),
                    StudentEntity(id = "s2", branchId = "b1", name = "Omar", gender = "MALE", roomId = "room_1", createdAt = now, updatedAt = now),
                    StudentEntity(id = "s3", branchId = "b1", name = "Ali", gender = "MALE", roomId = null, createdAt = now, updatedAt = now)
                )
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun createVm() = TeacherHomeworkViewModel(
        assignmentsRepository = assignmentsRepo,
        studentsRepository = studentsRepo,
        roomDao = db.roomDao(),
        sessionStore = fakeSessionStore
    )

    @Test
    fun testInitialDataLoading() = runBlocking {
        val vm = createVm()
        val state = vm.uiState.first { !it.isLoading }

        assertEquals(1, state.rooms.size)
        assertEquals(3, state.students.size)
        assertEquals(3, state.selectedStudentIds.size)
    }

    @Test
    fun testSelectRoom_filtersStudents() = runBlocking {
        val vm = createVm()
        vm.uiState.first { !it.isLoading }

        vm.selectRoom("room_1")
        val state = vm.uiState.first { it.selectedRoomId == "room_1" }

        assertEquals(2, state.selectedStudentIds.size)
        assertTrue(state.selectedStudentIds.contains("s1"))
        assertTrue(state.selectedStudentIds.contains("s2"))
        assertFalse(state.selectedStudentIds.contains("s3"))
    }

    @Test
    fun testSaveAsDraft_createsDraftAssignment() = runBlocking {
        val vm = createVm()
        vm.uiState.first { !it.isLoading }

        vm.updateTitle("حفظ سورة الكهف")
        vm.updateSubject("القرآن الكريم")
        vm.updateDueDate(LocalDate.of(2026, 11, 1))
        vm.toggleStudentSelection("s1")

        var doneAssignmentId: String? = null
        vm.saveAsDraft { id -> doneAssignmentId = id }

        assertNotNull(doneAssignmentId)
        val saved = assignmentsRepo.getById(doneAssignmentId!!)
        assertNotNull(saved)
        assertTrue(saved!!.isDraft)
        assertEquals("حفظ سورة الكهف", saved.title)
    }

    @Test
    fun testPublishNow_createsAndPublishesAssignment() = runBlocking {
        val vm = createVm()
        vm.uiState.first { !it.isLoading }

        vm.updateTitle("تلاوة سورة يس")
        vm.updateDueDate(null) // Optional null dueDate
        vm.toggleStudentSelection("s1")

        var publishedId: String? = null
        vm.publishNow { id -> publishedId = id }

        assertNotNull(publishedId)
        val saved = assignmentsRepo.getById(publishedId!!)
        assertNotNull(saved)
        assertFalse(saved!!.isDraft)
        assertNull(saved.dueDate)
    }

    @Test
    fun testValidation_emptyTitleSetsError() = runBlocking {
        val vm = createVm()
        vm.uiState.first { !it.isLoading }

        vm.updateTitle("")
        var doneCalled = false
        vm.publishNow { doneCalled = true }

        assertFalse(doneCalled)
        val state = vm.uiState.value
        assertEquals("يرجى كتابة عنوان للواجب", state.errorMessage)
    }
}
