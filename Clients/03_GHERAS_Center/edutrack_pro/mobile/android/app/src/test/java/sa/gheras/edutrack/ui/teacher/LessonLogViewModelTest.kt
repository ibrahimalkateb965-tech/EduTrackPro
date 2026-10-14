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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import sa.gheras.edutrack.data.db.GherasDatabase
import sa.gheras.edutrack.data.entity.LessonLogEntity
import sa.gheras.edutrack.data.entity.PendingWriteEntity
import sa.gheras.edutrack.data.entity.RoomEntity
import sa.gheras.edutrack.data.entity.ScheduleEntity
import sa.gheras.edutrack.data.repo.LessonLogsRepository
import sa.gheras.edutrack.data.repo.ScheduleRepository
import sa.gheras.edutrack.sync.Outbox
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LessonLogViewModelTest {

    private lateinit var db: GherasDatabase
    private lateinit var scheduleRepo: ScheduleRepository
    private lateinit var lessonLogsRepo: LessonLogsRepository
    private val now: Instant = Instant.parse("2026-10-11T08:00:00Z")
    private val today: LocalDate = LocalDate.parse("2026-10-11")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, GherasDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val outbox = Outbox(context, db)
        scheduleRepo = ScheduleRepository(db.scheduleDao())
        lessonLogsRepo = LessonLogsRepository(db.lessonLogDao(), outbox)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun room(id: String, name: String) = RoomEntity(
        id = id, branchId = "b1", name = name, groupName = "g",
        createdAt = now, updatedAt = now
    )

    private fun schedule(id: String, roomId: String) = ScheduleEntity(
        id = id,
        branchId = "b1",
        roomId = roomId,
        teacherUserId = "u1",
        day = "الأحد",
        startTime = LocalTime.of(8, 0),
        endTime = LocalTime.of(9, 30),
        subject = "القرآن الكريم",
        groupName = "حلقة أ",
        createdAt = now,
        updatedAt = now
    )

    private fun createVm(scheduleId: String = "sch1", dateStr: String = "2026-10-11") = LessonLogViewModel(
        scheduleId = scheduleId,
        dateStr = dateStr,
        scheduleRepository = scheduleRepo,
        roomDao = db.roomDao(),
        lessonLogsRepository = lessonLogsRepo
    )

    @Test
    fun `loads schedule and room name correctly`() = runBlocking {
        db.roomDao().upsertAll(listOf(room("r1", "قاعة الفاروق")))
        db.scheduleDao().upsertAll(listOf(schedule("sch1", "r1")))

        val vm = createVm()
        val state = vm.uiState.first { it.schedule != null }

        assertNotNull(state.schedule)
        assertEquals("القرآن الكريم", state.schedule?.subject)
        assertEquals("قاعة الفاروق", state.roomName)
        assertEquals(today, state.date)
        assertEquals("تمت", state.status)
        assertFalse(state.isDirty)
    }

    @Test
    fun `loads existing lesson log data when present`() = runBlocking {
        db.roomDao().upsertAll(listOf(room("r1", "قاعة الفاروق")))
        db.scheduleDao().upsertAll(listOf(schedule("sch1", "r1")))
        db.lessonLogDao().upsertAll(
            listOf(
                LessonLogEntity(
                    id = "log1",
                    branchId = "b1",
                    scheduleId = "sch1",
                    date = today,
                    status = "تمت",
                    covered = "سورة النبأ الآيات 1-15",
                    homework = "حفظ الآيات المتبقية",
                    notes = "تفاعل ممتاز من الطلاب",
                    teacherUserId = "u1",
                    createdAt = now,
                    updatedAt = now
                )
            )
        )

        val vm = createVm()
        val state = vm.uiState.first { it.covered.isNotBlank() }

        assertEquals("سورة النبأ الآيات 1-15", state.covered)
        assertEquals("حفظ الآيات المتبقية", state.homework)
        assertEquals("تفاعل ممتاز من الطلاب", state.notes)
        assertFalse(state.isDirty)
    }

    @Test
    fun `updating fields marks state dirty`() = runBlocking {
        db.roomDao().upsertAll(listOf(room("r1", "قاعة الفاروق")))
        db.scheduleDao().upsertAll(listOf(schedule("sch1", "r1")))

        val vm = createVm()
        vm.uiState.first { it.schedule != null }

        vm.updateCovered("درس جديد")
        vm.updateHomework("واجب منزلي")
        vm.updateNotes("ملاحظات هامة")
        vm.updateStatus("مؤجلة")

        val state = vm.uiState.value
        assertEquals("درس جديد", state.covered)
        assertEquals("واجب منزلي", state.homework)
        assertEquals("ملاحظات هامة", state.notes)
        assertEquals("مؤجلة", state.status)
        assertTrue(state.isDirty)
    }

    @Test
    fun `save persists record to dao and outbox`() = runBlocking {
        db.roomDao().upsertAll(listOf(room("r1", "قاعة الفاروق")))
        db.scheduleDao().upsertAll(listOf(schedule("sch1", "r1")))

        val vm = createVm()
        vm.uiState.first { it.schedule != null }

        vm.updateCovered("سورة الانفطار")
        vm.updateHomework("حل تمارين صفحة 20")

        val deferred = CompletableDeferred<Unit>()
        vm.save { deferred.complete(Unit) }
        deferred.await()

        assertFalse(vm.uiState.value.isSaving)
        assertFalse(vm.uiState.value.isDirty)

        // Verify in DB
        val saved = db.lessonLogDao().getByScheduleAndDate("sch1", today)
        assertNotNull(saved)
        assertEquals("سورة الانفطار", saved?.covered)
        assertEquals("حل تمارين صفحة 20", saved?.homework)

        // Verify outbox queued
        val pending = db.pendingWriteDao().listPending()
        assertTrue(pending.any { it.kind == PendingWriteEntity.KIND_LESSON_LOG })
    }
}
