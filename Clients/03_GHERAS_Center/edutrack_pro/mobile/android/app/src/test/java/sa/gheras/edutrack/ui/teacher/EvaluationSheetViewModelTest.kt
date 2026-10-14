package sa.gheras.edutrack.ui.teacher

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
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
import sa.gheras.edutrack.data.entity.EvaluationEntity
import sa.gheras.edutrack.data.entity.PendingWriteEntity
import sa.gheras.edutrack.data.entity.RoomEntity
import sa.gheras.edutrack.data.entity.ScheduleEntity
import sa.gheras.edutrack.data.entity.StudentEntity
import sa.gheras.edutrack.data.repo.EvaluationsRepository
import sa.gheras.edutrack.data.repo.ScheduleRepository
import sa.gheras.edutrack.data.repo.StudentsRepository
import sa.gheras.edutrack.sync.Outbox
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class EvaluationSheetViewModelTest {

    private lateinit var db: GherasDatabase
    private lateinit var outbox: Outbox
    private lateinit var studentsRepo: StudentsRepository
    private lateinit var scheduleRepo: ScheduleRepository
    private lateinit var evaluationsRepo: EvaluationsRepository
    private val now: Instant = Instant.parse("2026-10-11T08:00:00Z")
    private val testDate = LocalDate.parse("2026-10-11")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, GherasDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        outbox = Outbox(context, db)
        studentsRepo = StudentsRepository(db.studentDao())
        scheduleRepo = ScheduleRepository(db.scheduleDao())
        evaluationsRepo = EvaluationsRepository(db.evaluationDao(), outbox)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun room(id: String, name: String = "قاعة $id") = RoomEntity(
        id = id, branchId = "b1", name = name, groupName = "g",
        createdAt = now, updatedAt = now
    )

    private fun student(id: String, name: String, roomId: String) = StudentEntity(
        id = id, branchId = "b1", name = name, nationalId = null, birthDate = null,
        nationality = null, difficultyNotes = null, childNotes = null, fatherName = null,
        fatherPhone = null, motherName = null, motherPhone = null, guardianPhone = null,
        guardianRelation = null, pickupType = null, pickupName = null, pickupRelation = null,
        pickupPhone = null, previousSchool = null, previousLevel = null, educationNotes = null,
        roomId = roomId, groupName = null, createdAt = now, updatedAt = now
    )

    private fun schedule(id: String, roomId: String, subject: String) = ScheduleEntity(
        id = id,
        branchId = "b1",
        roomId = roomId,
        teacherUserId = "u1",
        day = "الأحد",
        startTime = LocalTime.of(8, 0),
        endTime = LocalTime.of(9, 30),
        subject = subject,
        groupName = "فوج أ",
        createdAt = now,
        updatedAt = now
    )

    private fun seedData() = runBlocking {
        db.roomDao().upsertAll(listOf(room("r1", "قاعة الفاروق")))
        db.studentDao().upsertAll(
            listOf(
                student("s1", "أحمد علي", "r1"),
                student("s2", "سارة محمد", "r1")
            )
        )
        db.scheduleDao().upsertAll(
            listOf(
                schedule("sch1", "r1", "القرآن"),
                schedule("sch2", "r1", "لغتي")
            )
        )
    }

    private fun createVm(
        roomId: String = "r1",
        dateStr: String = "2026-10-11",
        initialSubject: String = ""
    ) = EvaluationSheetViewModel(
        roomId = roomId,
        dateStr = dateStr,
        initialSubject = initialSubject,
        studentsRepository = studentsRepo,
        roomDao = db.roomDao(),
        scheduleRepository = scheduleRepo,
        evaluationsRepository = evaluationsRepo
    )

    private suspend fun loadedState(vm: EvaluationSheetViewModel): EvaluationSheetUiState =
        withTimeout(5_000) {
            vm.uiState.first { it.drafts.isNotEmpty() }
        }

    @Test
    fun `initializes with room students, available subjects from schedule, and default score 10`() = runBlocking {
        seedData()

        val vm = createVm()
        val state = loadedState(vm)

        assertEquals("قاعة الفاروق", state.roomName)
        assertEquals(testDate, state.date)
        assertEquals(listOf("القرآن", "لغتي"), state.availableSubjects)
        assertEquals("القرآن", state.selectedSubject)
        assertEquals(2, state.drafts.size)
        assertEquals(10.0, state.drafts[0].score, 0.001)
        assertEquals(10.0, state.drafts[1].score, 0.001)
        assertFalse(state.isDirty)
        assertFalse(state.isSaving)
    }

    @Test
    fun `loads existing evaluations for target date and selected subject`() = runBlocking {
        seedData()
        db.evaluationDao().upsertAll(
            listOf(
                EvaluationEntity(
                    id = "ev1",
                    branchId = "b1",
                    studentId = "s1",
                    subject = "القرآن",
                    evalType = "daily",
                    date = testDate,
                    value = 8.5,
                    teacherUserId = "u1",
                    createdAt = now,
                    updatedAt = now
                )
            )
        )

        val vm = createVm()
        val state = loadedState(vm)

        val s1Draft = state.drafts.first { it.student.id == "s1" }
        assertEquals(8.5, s1Draft.score, 0.001)

        val s2Draft = state.drafts.first { it.student.id == "s2" }
        assertEquals(10.0, s2Draft.score, 0.001)
    }

    @Test
    fun `setScore updates score clamped between 0 and 10 and sets dirty flag`() = runBlocking {
        seedData()
        val vm = createVm()
        loadedState(vm)

        vm.setScore("s1", 7.5)
        assertTrue(vm.uiState.value.isDirty)
        assertEquals(7.5, vm.uiState.value.drafts.first { it.student.id == "s1" }.score, 0.001)

        // Upper clamp
        vm.setScore("s1", 12.0)
        assertEquals(10.0, vm.uiState.value.drafts.first { it.student.id == "s1" }.score, 0.001)

        // Lower clamp
        vm.setScore("s1", -3.0)
        assertEquals(0.0, vm.uiState.value.drafts.first { it.student.id == "s1" }.score, 0.001)
    }

    @Test
    fun `updateScore applies delta clamped between 0 and 10`() = runBlocking {
        seedData()
        val vm = createVm()
        loadedState(vm)

        vm.updateScore("s1", -1.5)
        assertTrue(vm.uiState.value.isDirty)
        assertEquals(8.5, vm.uiState.value.drafts.first { it.student.id == "s1" }.score, 0.001)

        vm.updateScore("s1", 2.0)
        assertEquals(10.0, vm.uiState.value.drafts.first { it.student.id == "s1" }.score, 0.001)
    }

    @Test
    fun `selectSubject switches subject and reloads evaluations`() = runBlocking {
        seedData()
        db.evaluationDao().upsertAll(
            listOf(
                EvaluationEntity(
                    id = "ev_quran",
                    branchId = "b1",
                    studentId = "s1",
                    subject = "القرآن",
                    evalType = "daily",
                    date = testDate,
                    value = 9.0,
                    teacherUserId = "u1",
                    createdAt = now,
                    updatedAt = now
                ),
                EvaluationEntity(
                    id = "ev_lughati",
                    branchId = "b1",
                    studentId = "s1",
                    subject = "لغتي",
                    evalType = "daily",
                    date = testDate,
                    value = 7.0,
                    teacherUserId = "u1",
                    createdAt = now,
                    updatedAt = now
                )
            )
        )

        val vm = createVm()
        loadedState(vm)
        assertEquals(9.0, vm.uiState.value.drafts.first { it.student.id == "s1" }.score, 0.001)

        vm.selectSubject("لغتي")
        // selectSubject flips selectedSubject before loadData replaces the drafts,
        // so wait for the reloaded score rather than the subject alone.
        val updatedState = withTimeout(5_000) {
            vm.uiState.first { state ->
                state.selectedSubject == "لغتي" &&
                    state.drafts.firstOrNull { it.student.id == "s1" }?.score == 7.0
            }
        }
        assertEquals("لغتي", updatedState.selectedSubject)
        assertEquals(10.0, updatedState.drafts.first { it.student.id == "s2" }.score, 0.001)
    }

    @Test
    fun `save writes batch to outbox and invokes callback`() = runBlocking {
        seedData()
        val vm = createVm()
        loadedState(vm)
        vm.setScore("s1", 9.5)

        val savedDeferred = CompletableDeferred<Unit>()
        vm.save { savedDeferred.complete(Unit) }
        savedDeferred.await()

        val finalState = vm.uiState.first { !it.isSaving && !it.isDirty }
        assertFalse(finalState.isDirty)
        assertFalse(finalState.isSaving)

        val pending = db.pendingWriteDao().observePending().first()
        assertEquals(2, pending.size)
        assertTrue(pending.all { it.kind == PendingWriteEntity.KIND_DAILY_EVAL })
    }
}
