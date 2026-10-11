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
import sa.gheras.edutrack.data.entity.SkillProgressEntity
import sa.gheras.edutrack.data.entity.StudentEntity
import sa.gheras.edutrack.data.repo.StudentsRepository
import java.time.Instant
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChildSkillsViewModelTest {

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

    private fun skill(id: String, studentId: String, skillName: String, level: String) = SkillProgressEntity(
        id = id,
        branchId = "b1",
        studentId = studentId,
        subject = "مخارج الحروف",
        skill = skillName,
        level = level,
        date = today,
        note = "إتقان الحروف الحلقية",
        createdAt = now,
        updatedAt = now
    )

    private fun createVm(studentId: String = "s1") = ChildSkillsViewModel(
        studentId = studentId,
        studentsRepository = studentsRepo,
        skillProgressDao = db.skillProgressDao()
    )

    @Test
    fun `loads skills for student and populates uiState`() = runBlocking {
        db.studentDao().upsertAll(
            listOf(
                student("s1", "يوسف محمود"),
                student("s2", "طالب آخر")
            )
        )
        db.skillProgressDao().upsertAll(
            listOf(
                skill("sk1", "s1", "الإخفاء الحقيقي", "متقن"),
                skill("sk2", "s1", "القلقلة", "جيد جدا"),
                skill("sk3", "s2", "المد اللازم", "مبتدئ") // Another student
            )
        )

        val vm = createVm("s1")
        val state = vm.uiState.first { !it.isLoading && it.skills.isNotEmpty() }

        assertFalse(state.isLoading)
        assertEquals("يوسف محمود", state.studentName)
        assertEquals(2, state.skills.size)
        assertEquals("الإخفاء الحقيقي", state.skills[0].skill)
        assertEquals("متقن", state.skills[0].level)
    }
}
