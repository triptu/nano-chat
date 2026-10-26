package ai.tushar.nanochat.chat

import ai.tushar.nanochat.AppGraph
import ai.tushar.nanochat.data.Agent
import ai.tushar.nanochat.data.Generation
import ai.tushar.nanochat.data.Message
import ai.tushar.nanochat.model.ModelInfo
import ai.tushar.nanochat.model.ModelState
import ai.tushar.nanochat.model.PendingReply
import ai.tushar.nanochat.model.ReplyProblem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class ChatState(
    val agent: Agent? = null,
    val messages: List<Message> = emptyList(),
    val pending: PendingReply? = null,
    val problem: ReplyProblem? = null,
    val modelReady: Boolean = false,
    val loaded: Boolean = false,
)

class ChatViewModel(private val graph: AppGraph, val agentId: Long) : ViewModel() {
    var draft by mutableStateOf("")

    val state: StateFlow<ChatState> = combine(
        graph.dao.agent(agentId),
        graph.dao.messages(agentId),
        graph.engine.pending.map { it[agentId] },
        graph.engine.problems.map { it[agentId] },
        graph.models.state(ai.tushar.nanochat.model.ModelId.Nano),
    ) { agent, messages, pending, problem, model ->
        ChatState(agent, messages, pending, problem, model == ModelState.Ready, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatState())

    /** Dev tools only: the last request sent for this chat. */
    val lastGeneration: StateFlow<Generation?> =
        graph.dao.lastGeneration(agentId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val modelInfo: StateFlow<ModelInfo> = graph.models.infos.map { it.getValue(ai.tushar.nanochat.model.ModelId.Nano) }.stateIn(viewModelScope, SharingStarted.Eagerly, graph.models.infos.value.getValue(ai.tushar.nanochat.model.ModelId.Nano))

    fun send() {
        val text = draft
        if (text.isBlank() || state.value.pending != null) return
        if (graph.engine.send(agentId, text) != null) draft = ""
    }

    fun stop() = graph.engine.stop(agentId)
    fun retry() = graph.engine.retry(agentId)
}
