package ai.tushar.nanochat.model

import ai.tushar.nanochat.data.AttachmentKind
import ai.tushar.nanochat.data.Role

/** A photo or voice note inside a turn, as the model sees it. [modelPath] is the absolute path of the file sent to the model. */
data class TurnMedia(val kind: AttachmentKind, val modelPath: String, val durationMs: Long? = null)

data class Turn(val role: Role, val text: String, val media: List<TurnMedia> = emptyList())

/** What gets sent to the model for one reply, and how much history made it in. */
data class PromptPlan(
    val systemPrompt: String,
    val prompt: String,
    val useSystemInstruction: Boolean,
    val includedTurns: Int,
    val droppedTurns: Int,
    val inputTokens: Int,
    val budget: Int,
    /** Included turns, oldest first, with media beyond the limits replaced by a text marker. */
    val turns: List<Turn> = emptyList(),
    val mediaKept: MediaCount = MediaCount(),
    val mediaDropped: MediaCount = MediaCount(),
)

data class MediaCount(val images: Int = 0, val audio: Int = 0) {
    val any: Boolean get() = images > 0 || audio > 0
}

sealed interface PromptResult {
    data class Fits(val plan: PromptPlan) : PromptResult
    data class TooLong(val tokens: Int, val budget: Int) : PromptResult
}

/** Counts input tokens for a system prompt (null when it goes inline) and prompt text. */
fun interface TokenCounter {
    suspend fun count(systemPrompt: String?, prompt: String): Int
}

/** Media may use at most this share of the input budget; older photos and voice notes are dropped first. */
const val MediaBudgetShare = 0.4f

/**
 * Builds the prompt from the agent's personality plus as many recent turns as fit in [budget] tokens.
 * [newestFirst] must start with the user's latest message, which is always kept.
 */
class PromptWindow(private val agentName: String, private val userName: String) {

    fun systemPrompt(personality: String): String = buildString {
        append(personality.trim())
        if (userName.isNotBlank()) append("\nYou are chatting with ${userName.trim()}.")
        append("\nReply as $agentName with just your next message: 2 to 5 friendly, specific sentences. Use a numbered list only for step-by-step instructions.")
        append(" Don't write the other person's lines.")
    }

    /** The instructions block that starts the prompt when the model has no system instructions. */
    fun instructions(systemPrompt: String): String = "## Instructions\n$systemPrompt\n\n"

    fun render(systemPrompt: String, chronological: List<Turn>, inlineSystem: Boolean): String = buildString {
        if (inlineSystem) append(instructions(systemPrompt))
        append("## Conversation\n")
        for (turn in chronological) {
            append(if (turn.role == Role.User) "User" else agentName).append(": ").append(textWithMarkers(turn)).append('\n')
        }
        append(agentName).append(':')
    }

    suspend fun build(
        personality: String,
        newestFirst: List<Turn>,
        budget: Int,
        useSystemInstruction: Boolean,
        counter: TokenCounter,
        media: MediaSupport = MediaSupport.TextOnly,
    ): PromptResult {
        require(newestFirst.isNotEmpty() && newestFirst.first().role == Role.User)
        val system = systemPrompt(personality)
        val (pruned, kept, dropped, mediaTokens) = pruneMedia(newestFirst, media, (budget * MediaBudgetShare).toInt())
        suspend fun countFor(k: Int): Pair<String, Int> {
            val text = render(system, pruned.take(k).asReversed(), inlineSystem = !useSystemInstruction)
            return text to counter.count(if (useSystemInstruction) system else null, text) + mediaTokensIn(pruned.take(k), media)
        }
        fun plan(k: Int, text: String, tokens: Int): PromptPlan {
            val included = pruned.take(k)
            val keptInWindow = MediaCount(
                images = included.sumOf { t -> t.media.count { it.kind == AttachmentKind.Image } },
                audio = included.sumOf { t -> t.media.count { it.kind == AttachmentKind.Audio } },
            )
            return PromptPlan(
                systemPrompt = system,
                prompt = text,
                useSystemInstruction = useSystemInstruction,
                includedTurns = k,
                droppedTurns = newestFirst.size - k,
                inputTokens = tokens,
                budget = budget,
                turns = included.asReversed(),
                mediaKept = keptInWindow,
                mediaDropped = MediaCount(
                    images = dropped.images + kept.images - keptInWindow.images,
                    audio = dropped.audio + kept.audio - keptInWindow.audio,
                ),
            )
        }

        var k = estimateFit(system, pruned, budget - mediaTokens).coerceIn(1, pruned.size)
        var (text, tokens) = countFor(k)
        while (tokens > budget && k > 1) {
            k--
            countFor(k).let { text = it.first; tokens = it.second }
        }
        if (tokens > budget) return PromptResult.TooLong(tokens, budget)
        while (k < pruned.size) {
            val (nextText, nextTokens) = countFor(k + 1)
            if (nextTokens > budget) break
            k++; text = nextText; tokens = nextTokens
        }
        return PromptResult.Fits(plan(k, text, tokens))
    }

    private data class Pruned(val turns: List<Turn>, val kept: MediaCount, val dropped: MediaCount, val tokens: Int)

    /** Keeps the newest media that the backend accepts and that fits in [mediaBudget]; the rest become text markers. */
    private fun pruneMedia(newestFirst: List<Turn>, media: MediaSupport, mediaBudget: Int): Pruned {
        var images = 0
        var audio = 0
        var droppedImages = 0
        var droppedAudio = 0
        var used = 0
        val out = newestFirst.mapIndexed { i, turn ->
            if (turn.media.isEmpty()) return@mapIndexed turn
            val keep = turn.media.filter { m ->
                val cost = mediaCost(m, media)
                val allowed = when (m.kind) {
                    AttachmentKind.Image -> media.images && images < media.maxImages && (i == 0 || !media.imagesInNewestTurnOnly)
                    AttachmentKind.Audio -> media.audio && audio < media.maxAudioClips
                }
                // The newest message's media always goes in if the backend takes it at all.
                val fits = i == 0 || used + cost <= mediaBudget
                if (allowed && fits) {
                    used += cost
                    if (m.kind == AttachmentKind.Image) images++ else audio++
                    true
                } else {
                    if (m.kind == AttachmentKind.Image) droppedImages++ else droppedAudio++
                    false
                }
            }
            if (keep.size == turn.media.size) turn else turn.copy(media = keep, text = withDroppedMarkers(turn, keep))
        }
        return Pruned(out, MediaCount(images, audio), MediaCount(droppedImages, droppedAudio), used)
    }

    private fun mediaTokensIn(turns: List<Turn>, media: MediaSupport) = turns.sumOf { t -> t.media.sumOf { mediaCost(it, media) } }

    private fun mediaCost(m: TurnMedia, media: MediaSupport): Int = when (m.kind) {
        AttachmentKind.Image -> media.tokensPerImage
        AttachmentKind.Audio -> (((m.durationMs ?: 30_000) + 999) / 1000).toInt() * media.audioTokensPerSecond
    }

    /** The turn's text with "[photo]" style markers for media that was dropped, so the model knows it was there. */
    private fun withDroppedMarkers(turn: Turn, kept: List<TurnMedia>): String {
        val gone = turn.media - kept.toSet()
        val markers = gone.joinToString(" ") { marker(it) }
        return listOf(markers, turn.text).filter { it.isNotBlank() }.joinToString(" ")
    }

    /** Transcript text for a turn: kept media shows as a marker too, since a transcript can't hold the file. */
    private fun textWithMarkers(turn: Turn): String {
        val markers = turn.media.joinToString(" ") { marker(it) }
        return listOf(markers, turn.text.trim()).filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun marker(m: TurnMedia) = when (m.kind) {
        AttachmentKind.Image -> "[photo]"
        AttachmentKind.Audio -> "[voice note${m.durationMs?.let { ", ${(it + 500) / 1000} s" }.orEmpty()}]"
    }

    private fun estimateFit(system: String, newestFirst: List<Turn>, budget: Int): Int {
        var used = estimate(system) + 16
        var k = 0
        for (turn in newestFirst) {
            used += estimate(turn.text) + 4
            if (used > budget) break
            k++
        }
        return k
    }

    private fun estimate(text: String): Int = (text.length + 2) / 3
}
