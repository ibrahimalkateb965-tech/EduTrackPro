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
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import sa.gheras.edutrack.data.db.GherasDatabase
import sa.gheras.edutrack.data.entity.InstallmentEntity
import sa.gheras.edutrack.data.entity.ReceiptEntity
import sa.gheras.edutrack.data.entity.StudentEntity
import sa.gheras.edutrack.data.repo.FeesRepository
import sa.gheras.edutrack.data.repo.StudentsRepository
import java.time.Instant
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class FeesViewModelTest {

    private lateinit var db: GherasDatabase
    private val now: Instant = Instant.parse("2026-10-11T12:00:00Z")
    private val today: LocalDate = LocalDate.now()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, GherasDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun student(id: String, name: String) = StudentEntity(
        id = id, branchId = "b1", name = name, nationalId = "1000$id", birthDate = null,
        nationality = null, difficultyNotes = null, childNotes = null, fatherName = "الأب",
        fatherPhone = null, motherName = null, motherPhone = null, guardianPhone = "0501234567",
        guardianRelation = "أب", pickupType = null, pickupName = null, pickupRelation = null,
        pickupPhone = null, previousSchool = null, previousLevel = null, educationNotes = null,
        roomId = null, groupName = null, createdAt = now, updatedAt = now
    )

    private fun newVm(studentId: String? = null) = FeesViewModel(
        initialStudentId = studentId,
        studentsRepository = StudentsRepository(db.studentDao()),
        feesRepository = FeesRepository(db.installmentDao(), db.receiptDao())
    )

    @Test
    fun `when no children exist, state is empty and not loading`() = runBlocking {
        val vm = newVm()
        val state = withTimeout(5_000) { vm.uiState.first { !it.isLoading } }

        assertTrue(state.children.isEmpty())
        assertNull(state.selectedChild)
        assertEquals(0.0, state.summary.totalAmount, 0.001)
        assertEquals(0.0, state.summary.paidAmount, 0.001)
        assertEquals(0.0, state.summary.remainingAmount, 0.001)
    }

    @Test
    fun `summary accurately calculates total, paid, and remaining fees`() = runBlocking {
        db.studentDao().upsert(student("s1", "فهد"))

        val i1 = InstallmentEntity(
            id = "inst1", branchId = "b1", studentId = "s1", feePlanId = "fp1",
            seqNo = 1, dueDate = today.minusDays(30), amount = 500.0, paidAmount = 500.0,
            status = "paid", createdAt = now, updatedAt = now
        )
        val i2 = InstallmentEntity(
            id = "inst2", branchId = "b1", studentId = "s1", feePlanId = "fp1",
            seqNo = 2, dueDate = today.plusDays(15), amount = 500.0, paidAmount = 0.0,
            status = "pending", createdAt = now, updatedAt = now
        )
        val i3 = InstallmentEntity(
            id = "inst3", branchId = "b1", studentId = "s1", feePlanId = "fp1",
            seqNo = 3, dueDate = today.plusDays(45), amount = 500.0, paidAmount = 0.0,
            status = "pending", createdAt = now, updatedAt = now
        )
        db.installmentDao().upsertAll(listOf(i1, i2, i3))

        val r1 = ReceiptEntity(
            id = "rec1", branchId = "b1", paymentId = "pay1", studentId = "s1",
            installmentId = "inst1", receiptNo = 1001, amount = 500.0, method = "card",
            paidOn = today.minusDays(30), issuedOn = now, createdAt = now, updatedAt = now
        )
        db.receiptDao().upsertAll(listOf(r1))

        val vm = newVm()
        val state = withTimeout(5_000) { vm.uiState.first { !it.isLoading && it.installments.isNotEmpty() } }

        assertEquals("s1", state.selectedChild?.id)
        assertEquals(1500.0, state.summary.totalAmount, 0.001)
        assertEquals(500.0, state.summary.paidAmount, 0.001)
        assertEquals(1000.0, state.summary.remainingAmount, 0.001)
        assertEquals(3, state.installments.size)
        assertEquals(1, state.receipts.size)
    }

    @Test
    fun `switching child updates fees for the newly selected student`() = runBlocking {
        // StudentDao sorts by name ASC, so "أحمد" (s1) correctly precedes "سارة" (s2)
        db.studentDao().upsertAll(listOf(student("s1", "أحمد"), student("s2", "سارة")))

        db.installmentDao().upsertAll(listOf(
            InstallmentEntity(
                id = "inst_s1", branchId = "b1", studentId = "s1", feePlanId = "fp1",
                seqNo = 1, dueDate = today, amount = 1000.0, paidAmount = 300.0,
                status = "partial", createdAt = now, updatedAt = now
            )
        ))
        db.installmentDao().upsertAll(listOf(
            InstallmentEntity(
                id = "inst_s2", branchId = "b1", studentId = "s2", feePlanId = "fp2",
                seqNo = 1, dueDate = today, amount = 2000.0, paidAmount = 2000.0,
                status = "paid", createdAt = now, updatedAt = now
            )
        ))

        val vm = newVm()
        val stateChild1 = withTimeout(5_000) { vm.uiState.first { !it.isLoading } }
        assertEquals("s1", stateChild1.selectedChild?.id)
        assertEquals(1000.0, stateChild1.summary.totalAmount, 0.001)

        vm.selectChild("s2")
        val stateChild2 = withTimeout(5_000) { vm.uiState.first { it.selectedChild?.id == "s2" } }
        assertEquals(2000.0, stateChild2.summary.totalAmount, 0.001)
        assertEquals(2000.0, stateChild2.summary.paidAmount, 0.001)
        assertEquals(0.0, stateChild2.summary.remainingAmount, 0.001)
    }
}
