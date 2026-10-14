package sa.gheras.edutrack.ui.account

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import sa.gheras.edutrack.data.db.GherasDatabase
import sa.gheras.edutrack.data.local.session.FakeSessionStore
import sa.gheras.edutrack.data.remote.fakes.FakeAuthApi
import sa.gheras.edutrack.data.remote.fakes.FakeMeApi
import sa.gheras.edutrack.data.repo.SessionRepository

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChangePasswordViewModelTest {

    private lateinit var db: GherasDatabase
    private lateinit var fakeStore: FakeSessionStore
    private lateinit var fakeAuth: FakeAuthApi
    private lateinit var fakeMe: FakeMeApi
    private lateinit var sessionRepo: SessionRepository
    private lateinit var vm: ChangePasswordViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, GherasDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        fakeStore = FakeSessionStore()
        fakeAuth = FakeAuthApi()
        fakeMe = FakeMeApi()
        sessionRepo = SessionRepository(
            store = fakeStore,
            authApi = fakeAuth,
            meApi = fakeMe,
            db = db
        )
        vm = ChangePasswordViewModel(sessionRepo)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is empty and password visibility toggles`() {
        val state = vm.uiState.value
        assertEquals("", state.currentPassword)
        assertEquals("", state.newPassword)
        assertEquals("", state.confirmPassword)
        assertFalse(state.isCurrentVisible)
        assertNull(state.errorMessage)

        vm.toggleCurrentVisibility()
        vm.toggleNewVisibility()
        vm.toggleConfirmVisibility()

        val updated = vm.uiState.value
        assertTrue(updated.isCurrentVisible)
        assertTrue(updated.isNewVisible)
        assertTrue(updated.isConfirmVisible)
    }

    @Test
    fun `validates empty fields`() {
        var successCalled = false
        vm.changePassword { successCalled = true }

        assertFalse(successCalled)
        assertEquals("يرجى تعبئة جميع الحقول", vm.uiState.value.errorMessage)
    }

    @Test
    fun `validates minimum length of 8 characters`() {
        vm.onCurrentPasswordChange("oldPassword123")
        vm.onNewPasswordChange("short")
        vm.onConfirmPasswordChange("short")

        vm.changePassword {}
        assertEquals("كلمة المرور الجديدة يجب ألا تقل عن 8 أحرف", vm.uiState.value.errorMessage)
    }

    @Test
    fun `validates password confirmation match`() {
        vm.onCurrentPasswordChange("oldPassword123")
        vm.onNewPasswordChange("newSecret123")
        vm.onConfirmPasswordChange("different123")

        vm.changePassword {}
        assertEquals("كلمة المرور وتأكيدها غير متطابقين", vm.uiState.value.errorMessage)
    }

    @Test
    fun `validates new password is different from current`() {
        vm.onCurrentPasswordChange("samePassword123")
        vm.onNewPasswordChange("samePassword123")
        vm.onConfirmPasswordChange("samePassword123")

        vm.changePassword {}
        assertEquals("كلمة المرور الجديدة يجب أن تكون مختلفة عن الحالية", vm.uiState.value.errorMessage)
    }

    @Test
    fun `successful password change sets success message and invokes callback`() = runBlocking {
        vm.onCurrentPasswordChange("oldPassword123")
        vm.onNewPasswordChange("newSecret123")
        vm.onConfirmPasswordChange("newSecret123")

        val deferred = CompletableDeferred<Unit>()
        vm.changePassword { deferred.complete(Unit) }
        deferred.await()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
        assertEquals("تم تغيير كلمة المرور بنجاح", state.successMessage)
    }
}
