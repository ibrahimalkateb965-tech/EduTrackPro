package sa.gheras.edutrack.ui.guardian

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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import sa.gheras.edutrack.data.db.GherasDatabase
import sa.gheras.edutrack.data.entity.LessonLogEntity
import sa.gheras.edutrack.data.entity.RoomEntity
import sa.gheras.edutrack.data.entity.ScheduleEntity
import sa.gheras.edutrack.data.entity.StudentEntity
import sa.gheras.edutrack.data.repo.StudentsRepository
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChildLessonsViewModelTest {

    private lateinit var db: GherasDatabase
    private lateinit var studentsRepo: StudentsRepository
    private val now: Instant = Instant.now()
    private val today: LocalDate get() = LocalDate.now()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, GherasDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        studentsRepo = StudentsRepository(db.studentDao())
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun student(id: String, name: String) = StudentEntity(
        id = id, branchId = "b1", name = name, nationalId = null, birthDate = null,
        nationality = null, difficultyNotes = null, childNotes = null, fatherName = null,
        fatherPhone = null, motherName = null, motherPhone = null, guardianPhone = null,
        guardianRelation = null, pickupType = null, pickupName = null, pickupRelation = null,
        pickupPhone = null, previousSchool = null, previousLevel = null, educationNotes = null,
        roomId = null, groupName = null, createdAt = now, updatedAt = now
    )

    private fun room(id: String) = RoomEntity(
        id = id,
        branchId = "b1",
        name = "قاعة الفرقان",
        groupName = "حلقة أبي بكر",
        createdAt = now,
        updatedAt = now
    )

    private fun schedule(id: String, roomId: String, subject: String) = ScheduleEntity(
        id = id,
        branchId = "b1",
        roomId = roomId,
        teacherUserId = "u1",
        day = "الأحد",
        startTime = LocalTime.of(16, 0),
        endTime = LocalTime.of(17, 30),
        subject = subject,
        groupName = "حلقة أبي بكر",
        createdAt = now,
        updatedAt = now
    )

    private fun lessonLog(id: String, scheduleId: String, date: LocalDate, covered: String) = LessonLogEntity(
        id = id,
        branchId = "b1",
        scheduleId = scheduleId,
        date = date,
        status = "completed",
        covered = covered,
        homework = "حفظ الصفحة التالية",
        notes = "تفاعل ممتاز من الطلاب",
        teacherUserId = "u1",
        createdAt = now,
        updatedAt = now
    )

    private fun createVm(studentId: String = "s1") = ChildLessonsViewModel(
        studentId = studentId,
        studentsRepository = studentsRepo,
        scheduleDao = db.scheduleDao(),
        lessonLogDao = db.lessonLogDao()
    )

    @Test
    fun `loads student lessons and maps subject correctly from schedules`() = runBlocking {
        db.studentDao().upsertAll(listOf(student("s1", "حمزة إبراهيم")))
        db.roomDao().upsertAll(listOf(room("r1")))
        db.scheduleDao().upsertAll(
            listOf(
                schedule("sch1", "r1", "تجويد القرآن الكريم"),
                schedule("sch2", "r1", "تفسير جزء عم")
            )
        )
        db.lessonLogDao().upsertAll(
            listOf(
                lessonLog("log1", "sch1", today, "أحكام النون الساكنة والتنوين"),
                lessonLog("log2", "sch2", today.minusDays(2), "تفسير سورة التكاثر"),
                // Old log out of range
                lessonLog("log3", "sch1", today.minusDays(70), "الاستعاذة والبسملة")
            )
        )

        val vm = createVm("s1")
        val state = vm.uiState.first { !it.isLoading && it.items.isNotEmpty() }

        assertFalse(state.isLoading)
        assertEquals("حمزة إبراهيم", state.studentName)
        assertEquals(2, state.items.size)
        assertEquals("تجويد القرآن الكريم", state.items[0].subject)
        assertEquals("أحكام النون الساكنة والتنوين", state.items[0].log.covered)
        assertEquals("تفسير جزء عم", state.items[1].subject)
    }
}
