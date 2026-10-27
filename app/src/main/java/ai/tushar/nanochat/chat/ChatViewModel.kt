package ai.tushar.nanochat.chat

import ai.tushar.nanochat.AppGraph
import ai.tushar.nanochat.attachments.DraftAttachment
import ai.tushar.nanochat.attachments.Recording
import ai.tushar.nanochat.attachments.VoiceRecorder
import ai.tushar.nanochat.data.Agent
import ai.tushar.nanochat.data.Attachment
import ai.tushar.nanochat.data.AttachmentKind
import ai.tushar.nanochat.data.Generation
import ai.tushar.nanochat.data.Message
import ai.tushar.nanochat.data.ToolStep
import ai.tushar.nanochat.model.MediaSupport
import ai.tushar.nanochat.model.ModelId
import ai.tushar.nanochat.model.ModelInfo
import ai.tushar.nanochat.model.ModelState
import ai.tushar.nanochat.model.PendingReply
import ai.tushar.nanochat.model.ReplyProblem
import ai.tushar.nanochat.settings.BackendChoice
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/** The model a chat talks through, and how ready it is. */
data class ChatModel(
    val id: ModelId,
    val state: ModelState,
    val info: ModelInfo,
) {
    val ready: Boolean get() = state == ModelState.Ready
    val media: MediaSupport get() = info.media
}

data class ChatState(
    val agent: Agent? = null,
    val messages: List<Message> = emptyList(),
    /** Photos and voice notes by message id. */
    val attachments: Map<Long, List<Attachment>> = emptyMap(),
    /** Handset tool steps by the id of the reply that ran them. */
    val steps: Map<Long, List<ToolStep>> = emptyMap(),
    val pending: PendingReply? = null,
    val problem: ReplyProblem? = null,
    val model: ChatModel? = null,
    val loaded: Boolean = false,
)

/** Why an attachment couldn't be added, worded for the person chatting. */
enum class AttachProblem(val message: String) {
    PhotoFailed("Couldn't open that photo. Try another one?"),
    MicFailed("Couldn't start the microphone. Try again in a moment."),
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(private val graph: AppGraph, val agentId: Long) : ViewModel() {
    var draft by mutableStateOf("")
    var attachProblem by mutableStateOf<AttachProblem?>(null)

    private val modelId = combine(graph.dao.agent(agentId), graph.settings.defaultModel) { agent, default ->
        ModelId.parse(agent?.model) ?: default
    }.distinctUntilChanged()

    private val model = combine(modelId, graph.models.states, graph.models.infos) { id, states, infos ->
        ChatModel(id, states.getValue(id), infos.getValue(id))
    }.distinctUntilChanged()

    private class Saved(val agent: Agent?, val messages: List<Message>, val attachments: List<Attachment>, val steps: List<ToolStep>)

    val state: StateFlow<ChatState> = combine(
        combine(graph.dao.agent(agentId), graph.dao.messages(agentId), graph.dao.attachments(agentId), graph.dao.toolSteps(agentId), ::Saved),
        graph.engine.pending.map { it[agentId] },
        graph.engine.problems.map { it[agentId] },
        model,
    ) { saved, pending, problem, model ->
        ChatState(
            saved.agent, saved.messages, saved.attachments.groupBy { it.messageId }, saved.steps.groupBy { it.messageId },
            pending, problem, model, loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatState())

    val drafts: StateFlow<List<DraftAttachment>> =
        graph.drafts.of(agentId).stateIn(viewModelScope, SharingStarted.Eagerly, graph.drafts.now(agentId))

    private val recorder = VoiceRecorder(viewModelScope)
    val recording: StateFlow<Recording?> = recorder.recording

    /** Dev tools only: the last request sent for this chat. */
    val lastGeneration: StateFlow<Generation?> =
        graph.dao.lastGeneration(agentId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Dev tools only: every model's facts, to describe how the last request was sent. */
    val modelInfos: StateFlow<Map<ModelId, ModelInfo>> = graph.models.infos

    init {
        // Load the chat's model as soon as it's usable and the app is on screen, so the first reply doesn't wait for it.
        // Coming back to the app loads it again if it was freed in the background.
        val onScreen = ProcessLifecycleOwner.get().lifecycle.currentStateFlow.map { it.isAtLeast(Lifecycle.State.STARTED) }
        viewModelScope.launch {
            combine(model, onScreen) { m, shown -> m.id.takeIf { m.ready && shown } }
                .distinctUntilChanged().filterNotNull().collect { graph.models.warm(it) }
        }
    }

    fun send() {
        val text = draft
        val attachments = drafts.value
        if ((text.isBlank() && attachments.isEmpty()) || state.value.pending != null) return
        if (graph.engine.send(agentId, text, attachments) != null) {
            draft = ""
            graph.drafts.take(agentId)
        }
    }

    fun file(name: String): File = graph.attachments.file(name)

    fun stop() = graph.engine.stop(agentId)
    fun retry() = graph.engine.retry(agentId)

    fun download(id: ModelId) = graph.models.download(id)
    fun cancelDownload(id: ModelId) = graph.models.cancelDownload(id)
    fun recheck(id: ModelId) = graph.models.refresh(id)
    fun useFakeModels() = viewModelScope.launch { graph.settings.setBackendChoice(BackendChoice.Fake) }

    /** Switches this companion to [id], e.g. from the download screen when the user doesn't want to wait. */
    fun useModel(id: ModelId) = viewModelScope.launch {
        val agent = graph.dao.agentNow(agentId) ?: return@launch
        graph.dao.updateAgent(agent.copy(model = id.name))
    }

    /** How many more photos this message may carry. */
    fun photosLeft(): Int {
        val media = state.value.model?.media ?: return 0
        return (media.maxImages - drafts.value.count { it.kind == AttachmentKind.Image }).coerceAtLeast(0)
    }

    fun canAddVoiceNote(): Boolean {
        val media = state.value.model?.media ?: return false
        return media.audio && drafts.value.count { it.kind == AttachmentKind.Audio } < media.maxAudioClips
    }

    /** Copies picked or captured photos in as drafts, up to the model's limit. [then] runs once they're copied. */
    fun addPhotos(uris: List<Uri>, then: () -> Unit = {}) = viewModelScope.launch {
        try {
            for (uri in uris.take(photosLeft())) {
                runCatching { graph.attachments.importImage(uri) }
                    .onSuccess { graph.drafts.add(agentId, it) }
                    .onFailure { Log.w("NanoChat", "Couldn't import $uri", it); attachProblem = AttachProblem.PhotoFailed }
            }
        } finally {
            then()
        }
    }

    fun removeDraft(d: DraftAttachment) {
        graph.drafts.remove(agentId, d)
        graph.attachments.discard(d)
    }

    fun startRecording() {
        if (!canAddVoiceNote()) return
        val file = graph.attachments.newFile("wav")
        graph.drafts.recordingStarted(file.name)
        val started = recorder.start(file) { f, ms -> viewModelScope.launch { keepVoiceNote(f, ms) } }
        if (!started) {
            graph.drafts.recordingEnded(file.name)
            file.delete()
            attachProblem = AttachProblem.MicFailed
        }
    }

    /** Stops recording and keeps the note as a draft. With [andSend], sends the message right away. */
    fun stopRecording(andSend: Boolean = false) = viewModelScope.launch {
        val (file, ms) = recorder.stop() ?: return@launch
        keepVoiceNote(file, ms)
        if (andSend) send()
    }

    fun cancelRecording() = viewModelScope.launch {
        val file = recorder.recording.value?.file
        recorder.cancel()
        file?.let { graph.drafts.recordingEnded(it.name) }
    }

    private fun keepVoiceNote(file: File, ms: Long) {
        graph.drafts.recordingEnded(file.name)
        // Anything shorter than half a second is a mis-tap.
        if (ms < 500) { file.delete(); return }
        graph.drafts.add(agentId, graph.attachments.audioDraft(file, ms))
    }

    override fun onCleared() {
        // A recording can't outlive its screen; keep what was said so far.
        if (recorder.recording.value != null) graph.scope.launch { recorder.stop()?.let { (f, ms) -> keepVoiceNote(f, ms) } }
    }
}
