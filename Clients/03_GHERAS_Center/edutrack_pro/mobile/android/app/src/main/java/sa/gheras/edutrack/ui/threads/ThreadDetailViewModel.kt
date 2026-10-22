package sa.gheras.edutrack.ui.threads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import sa.gheras.edutrack.data.entity.ThreadEntity
import sa.gheras.edutrack.data.entity.ThreadMessageEntity
import sa.gheras.edutrack.data.repo.ThreadsRepository
import java.util.UUID

data class ThreadDetailUiState(
    val thread: ThreadEntity? = null,
    val messages: List<ThreadMessageEntity> = emptyList(),
    val currentUserId: String = "",
    val isLoading: Boolean = false,
    val isSending: Boolean = false,
    val errorMessage: String? = null,
    val messageText: String = "",
    val activeClientMessageId: String = ""
)

class ThreadDetailViewModel(
    val threadId: String,
    val currentUserId: String,
    private val threadsRepository: ThreadsRepository,
    initialClientMessageId: String? = null
) : ViewModel() {

    private val _thread = MutableStateFlow<ThreadEntity?>(null)
    private val _isLoading = MutableStateFlow(false)
    private val _isSending = MutableStateFlow(false)
    private val _errorMessage = MutableStateFlow<String?>(null)
    private val _messageText = MutableStateFlow("")
    private val _activeClientMessageId = MutableStateFlow(initialClientMessageId ?: UUID.randomUUID().toString())

    val activeClientMessageId: String
        get() = _activeClientMessageId.value

    val messageText: String
        get() = _messageText.value

    private val _opStatus = combine(_isLoading, _isSending, _errorMessage) { loading, sending, error ->
        Triple(loading, sending, error)
    }

    private val _inputState = combine(_messageText, _activeClientMessageId) { text, clientId ->
        text to clientId
    }

    val uiState: StateFlow<ThreadDetailUiState> = combine(
        _thread,
        threadsRepository.observeMessages(threadId),
        _opStatus,
        _inputState
    ) { thread, messages, (loading, sending, error), (text, clientId) ->
        ThreadDetailUiState(
            thread = thread,
            messages = messages,
            currentUserId = currentUserId,
            isLoading = loading,
            isSending = sending,
            errorMessage = error,
            messageText = text,
            activeClientMessageId = clientId
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        ThreadDetailUiState(currentUserId = currentUserId, activeClientMessageId = _activeClientMessageId.value)
    )

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            threadsRepository.refreshMessages(threadId)
                .onFailure { _errorMessage.value = it.message }
            threadsRepository.markRead(threadId)
            _isLoading.value = false
        }
    }

    fun onMessageTextChanged(newText: String) {
        if (newText != _messageText.value) {
            _messageText.value = newText
            // M3: When user edits the text, mint a fresh ID for the modified body
            _activeClientMessageId.value = UUID.randomUUID().toString()
        }
    }

    fun sendMessage(
        body: String? = null,
        attachmentStorageKey: String? = null,
        attachmentType: String? = null,
        attachmentName: String? = null,
        attachmentSizeBytes: Long? = null,
        clientMessageId: String? = null
    ) {
        if (body != null && body != _messageText.value) {
            _messageText.value = body
            if (clientMessageId == null) {
                _activeClientMessageId.value = UUID.randomUUID().toString()
            }
        }
        if (clientMessageId != null) {
            _activeClientMessageId.value = clientMessageId
        }

        val textToSend = _messageText.value
        if (textToSend.isBlank()) return
        val clientMsgId = _activeClientMessageId.value

        viewModelScope.launch {
            // M3: Clear the error at the start of sendMessage
            _errorMessage.value = null
            _isSending.value = true

            threadsRepository.sendMessage(
                threadId = threadId,
                body = textToSend.trim(),
                attachmentStorageKey = attachmentStorageKey,
                attachmentType = attachmentType,
                attachmentName = attachmentName,
                attachmentSizeBytes = attachmentSizeBytes,
                clientMessageId = clientMsgId
            ).onSuccess {
                // M3: On success, clear text and mint a new UUID for the next message
                _messageText.value = ""
                _activeClientMessageId.value = UUID.randomUUID().toString()
            }.onFailure {
                // M3: On failure, keep text and keep activeClientMessageId intact
                _errorMessage.value = it.message ?: "تعذر إرسال الرسالة"
            }
            _isSending.value = false
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }
}
