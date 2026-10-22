package sa.gheras.edutrack.ui.threads

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
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
import sa.gheras.edutrack.data.entity.RoomEntity
import sa.gheras.edutrack.data.entity.StudentEntity
import sa.gheras.edutrack.data.entity.ThreadEntity
import sa.gheras.edutrack.data.remote.ThreadsApi
import sa.gheras.edutrack.data.remote.dto.CreateThreadRequest
import sa.gheras.edutrack.data.remote.dto.DailySummaryDto
import sa.gheras.edutrack.data.remote.dto.DeliveredResponse
import sa.gheras.edutrack.data.remote.dto.ReadResponse
import sa.gheras.edutrack.data.remote.dto.SendMessageRequest
import sa.gheras.edutrack.data.remote.dto.ThreadDto
import sa.gheras.edutrack.data.remote.dto.ThreadMessageDto
import sa.gheras.edutrack.data.remote.dto.UpsertDailySummaryRequest
import sa.gheras.edutrack.data.repo.ThreadsRepository
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ThreadDetailViewModelTest {

    private lateinit var db: GherasDatabase
    private lateinit var fakeApi: FakeTestThreadsApi
    private lateinit var repository: ThreadsRepository
    private lateinit var viewModel: ThreadDetailViewModel
    private val testDispatcher = UnconfinedTestDispatcher()

    private class FakeTestThreadsApi : ThreadsApi {
        val sentRequests = mutableListOf<SendMessageRequest>()
        var shouldFailSendMessage = false

        override suspend fun listThreads(studentId: String?): List<ThreadDto> = emptyList()
        override suspend fun createThread(body: CreateThreadRequest): ThreadDto = throw NotImplementedError()
        override suspend fun getThread(threadId: String): ThreadDto = throw NotImplementedError()
        override suspend fun listMessages(threadId: String, limit: Int): List<ThreadMessageDto> = emptyList()

        override suspend fun sendMessage(threadId: String, body: SendMessageRequest): ThreadMessageDto {
            sentRequests.add(body)
            if (shouldFailSendMessage) {
                throw IOException("Simulated network failure")
            }
            return ThreadMessageDto(
                id = UUID.randomUUID().toString(),
                branchId = "00000000-0000-0000-0000-000000000001",
                threadId = threadId,
                senderUserId = "user_test",
                senderRole = "guardian",
                body = body.body,
                clientMessageId = body.clientMessageId,
                sentAt = "2026-10-22T20:00:00Z"
            )
        }

        override suspend fun markDelivered(threadId: String): DeliveredResponse = DeliveredResponse(0)
        override suspend fun markRead(threadId: String): ReadResponse = ReadResponse(0)

        override suspend fun listDailySummaries(
            roomId: String?,
            summaryDate: String?,
            isPublished: Boolean?
        ): List<DailySummaryDto> = emptyList()

        override suspend fun getDailySummary(summaryId: String): DailySummaryDto = throw NotImplementedError()
        override suspend fun upsertDailySummary(body: UpsertDailySummaryRequest): DailySummaryDto = throw NotImplementedError()
        override suspend fun publishDailySummary(summaryId: String): DailySummaryDto = throw NotImplementedError()

        override suspend fun listGuardianDailySummaries(
            studentId: String,
            summaryDate: String?
        ): List<DailySummaryDto> = emptyList()
    }

    @Before
    fun setUp() = runTest {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, GherasDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val now = Instant.now()
        // Seed foreign key hierarchy: Room -> Student -> Thread
        db.roomDao().upsert(
            RoomEntity(
                id = "room_1",
                branchId = null,
                name = "Room 1",
                groupName = "Group 1",
                createdAt = now,
                updatedAt = now
            )
        )
        db.studentDao().upsert(
            StudentEntity(
                id = "student_1",
                branchId = null,
                name = "Ahmad",
                nationalId = null,
                birthDate = LocalDate.parse("2016-05-01"),
                nationality = "SA",
                roomId = "room_1",
                createdAt = now,
                updatedAt = now
            )
        )
        db.threadDao().upsertThread(
            ThreadEntity(
                id = "thread-123",
                branchId = null,
                studentId = "student_1",
                guardianUserId = "user_test",
                staffUserId = "user_staff",
                subject = "موضوع التجربة",
                lastMessageAt = now,
                createdAt = now,
                updatedAt = now
            )
        )

        fakeApi = FakeTestThreadsApi()
        repository = ThreadsRepository(
            threadDao = db.threadDao(),
            threadsApi = fakeApi,
            ioDispatcher = testDispatcher
        )
        viewModel = ThreadDetailViewModel(
            threadId = "thread-123",
            currentUserId = "user_test",
            threadsRepository = repository
        )
    }

    @After
    fun tearDown() {
        if (this::viewModel.isInitialized) {
            viewModel.viewModelScope.coroutineContext.cancelChildren()
            viewModel.viewModelScope.cancel()
        }
        db.close()
        Dispatchers.resetMain()
    }

    private fun awaitState(predicate: (ThreadDetailUiState) -> Boolean): ThreadDetailUiState = runBlocking {
        withTimeout(5_000) { viewModel.uiState.first(predicate) }
    }

    @Test
    fun retryAfterFailureReusesClientMessageId() = runBlocking {
        fakeApi.shouldFailSendMessage = true
        viewModel.onMessageTextChanged("رسالة هامة")
        val initialId = viewModel.activeClientMessageId
        awaitState { it.messageText == "رسالة هامة" }

        viewModel.sendMessage()
        val failed = awaitState { it.errorMessage != null && !it.isSending }
        assertEquals("رسالة هامة", failed.messageText)
        assertEquals(initialId, viewModel.activeClientMessageId)
        assertEquals(1, fakeApi.sentRequests.size)
        assertEquals(initialId, fakeApi.sentRequests[0].clientMessageId)

        fakeApi.shouldFailSendMessage = false
        viewModel.sendMessage()
        val ok = awaitState { it.messageText == "" && !it.isSending }
        assertNull(ok.errorMessage)
        assertEquals(2, fakeApi.sentRequests.size)
        assertEquals(initialId, fakeApi.sentRequests[1].clientMessageId)
    }

    @Test
    fun sendAfterSuccessGetsNewClientMessageId() = runBlocking {
        viewModel.onMessageTextChanged("الرسالة الأولى")
        val firstId = viewModel.activeClientMessageId
        awaitState { it.messageText == "الرسالة الأولى" }

        viewModel.sendMessage()
        awaitState { it.messageText == "" && !it.isSending }
        assertEquals(1, fakeApi.sentRequests.size)
        assertEquals(firstId, fakeApi.sentRequests[0].clientMessageId)
        val secondId = viewModel.activeClientMessageId
        assertTrue("A fresh clientMessageId must be minted after success", firstId != secondId)

        viewModel.onMessageTextChanged("الرسالة الثانية")
        val currentId = viewModel.activeClientMessageId
        awaitState { it.messageText == "الرسالة الثانية" }
        viewModel.sendMessage()
        awaitState { it.messageText == "" && !it.isSending }
        assertEquals(2, fakeApi.sentRequests.size)
        assertEquals(currentId, fakeApi.sentRequests[1].clientMessageId)
        assertTrue("Second send must use a new clientMessageId", fakeApi.sentRequests[1].clientMessageId != firstId)
    }

    @Test
    fun editingTextAfterFailureMintsNewClientMessageId() = runBlocking {
        fakeApi.shouldFailSendMessage = true
        viewModel.onMessageTextChanged("نص أصلي")
        val initialId = viewModel.activeClientMessageId
        awaitState { it.messageText == "نص أصلي" }

        viewModel.sendMessage()
        val failed = awaitState { it.errorMessage != null && !it.isSending }
        assertEquals(initialId, viewModel.activeClientMessageId)
        assertEquals("نص أصلي", failed.messageText)

        viewModel.onMessageTextChanged("نص معدل")
        val editedId = viewModel.activeClientMessageId
        awaitState { it.messageText == "نص معدل" }
        assertTrue("Editing text must generate a new clientMessageId", initialId != editedId)

        fakeApi.shouldFailSendMessage = false
        viewModel.sendMessage()
        awaitState { it.messageText == "" && !it.isSending }
        assertEquals(2, fakeApi.sentRequests.size)
        assertEquals(editedId, fakeApi.sentRequests[1].clientMessageId)
    }
}
