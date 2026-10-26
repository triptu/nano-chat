package ai.tushar.nanochat.chat

import ai.tushar.nanochat.attachments.DraftAttachment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * Photos and voice notes picked in each chat's composer but not sent yet. App-wide, so drafts survive rotation and
 * leaving the chat, and so cleanup can keep every draft's files no matter which chat it belongs to.
 */
class ComposerDrafts {
    private val byAgent = MutableStateFlow<Map<Long, List<DraftAttachment>>>(emptyMap())
    /** Voice notes being recorded right now. Their files exist but aren't drafts yet. */
    private val recording = ConcurrentHashMap.newKeySet<String>()

    fun of(agentId: Long): Flow<List<DraftAttachment>> = byAgent.map { it[agentId].orEmpty() }.distinctUntilChanged()
    fun now(agentId: Long): List<DraftAttachment> = byAgent.value[agentId].orEmpty()

    fun add(agentId: Long, d: DraftAttachment) = byAgent.update { it + (agentId to it[agentId].orEmpty() + d) }
    fun remove(agentId: Long, d: DraftAttachment) = byAgent.update { it + (agentId to it[agentId].orEmpty() - d) }

    /** Empties the chat's drafts without deleting their files, because they were just sent. */
    fun take(agentId: Long): List<DraftAttachment> {
        val taken = now(agentId)
        byAgent.update { it - agentId }
        return taken
    }

    fun recordingStarted(file: String) { recording += file }
    fun recordingEnded(file: String) { recording -= file }

    /** Every file a draft or a recording still needs. */
    fun files(): Set<String> = byAgent.value.values.flatten().flatMap { listOf(it.file, it.modelFile) }.toSet() + recording
}
