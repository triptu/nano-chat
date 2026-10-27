package ai.tushar.nanochat.model

import ai.tushar.nanochat.handset.ToolRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import java.time.Duration

enum class Availability { Unavailable, Downloadable, Downloading, Available }

sealed interface DownloadEvent {
    data class Started(val totalBytes: Long?) : DownloadEvent
    data class Progress(val downloadedBytes: Long) : DownloadEvent
    data object Completed : DownloadEvent
    data class Failed(val error: ModelException) : DownloadEvent
}

/** The on-device models a companion can talk through. Stored by name on [ai.tushar.nanochat.data.Agent.model]. */
enum class ModelId(val label: String, val blurb: String) {
    Nano("Gemini Nano", "Built into Android. Fast, no download."),
    GemmaE4B("Gemma 4 E4B", "Smarter, and understands photos and voice notes. Needs a one-time download."),
    ;

    companion object {
        val Default = Nano
        fun parse(name: String?): ModelId? = entries.firstOrNull { it.name == name }
    }
}

/** What media a backend can take in one request, and what each piece costs in input tokens. */
data class MediaSupport(
    /** Most images one request may carry. 0 means no images. */
    val maxImages: Int,
    /** True when only the newest user message may carry images (ML Kit takes one image per request). */
    val imagesInNewestTurnOnly: Boolean,
    val tokensPerImage: Int,
    val audio: Boolean,
    val maxAudioClips: Int = if (audio) 1 else 0,
    val audioTokensPerSecond: Int = 0,
) {
    val images: Boolean get() = maxImages > 0

    companion object {
        val TextOnly = MediaSupport(maxImages = 0, imagesInNewestTurnOnly = true, tokensPerImage = 0, audio = false)
    }
}

data class GenerateRequest(
    val systemPrompt: String?,
    /** The rendered transcript, for backends that take one string. */
    val prompt: String,
    val temperature: Float,
    val topK: Int,
    val maxOutputTokens: Int,
    /** Fixed start of the prompt the model may cache between requests; [prompt] is the part after it. */
    val promptPrefix: String? = null,
    /** The same turns as structured messages, oldest first, ending with the user. Media already pruned to fit. */
    val turns: List<Turn> = emptyList(),
    /** Identifies the chat, so a backend can keep its conversation state between replies. */
    val chatKey: Long = 0,
    /** Tools the model may call. A backend with [ModelBackend.nativeTools] declares them; others get them in the prompt. */
    val tools: List<ToolSpec> = emptyList(),
)

/** A tool as declared to a model that calls tools natively. [parameters] is a JSON schema object. */
data class ToolSpec(val name: String, val description: String, val parameters: JsonObject)

/** Speed facts a backend reports for one reply. */
data class RunStats(
    val prefillTokensPerSecond: Float? = null,
    val decodeTokensPerSecond: Float? = null,
    /** Tokens the model actually prefilled for this reply (less than the prompt when it reused its cache). */
    val prefillTokens: Int? = null,
    val decodeTokens: Int? = null,
)

/**
 * One streamed piece of a reply. [finishReason] is set on the last chunk only, and [stats] may come with it.
 * [toolCalls] come from backends that call tools natively.
 */
data class GenerateChunk(val text: String, val finishReason: String?, val stats: RunStats? = null, val toolCalls: List<ToolRequest> = emptyList())

enum class ModelErrorKind { Busy, Quota, Background, TooLarge, NotReady, Other }

class ModelException(
    val kind: ModelErrorKind,
    val code: Int?,
    val codeName: String,
    message: String?,
    val retryAfter: Duration = Duration.ZERO,
) : Exception(message)

data class BackendInfo(
    val baseModelName: String?,
    val cachingAvailable: Boolean,
    val aicoreVersion: String? = null,
    /** "GPU" or "CPU" once a LiteRT engine is loaded. */
    val accelerator: String? = null,
    val loadMs: Long? = null,
    val speculativeDecoding: Boolean? = null,
    val fileBytes: Long? = null,
)

enum class BackendKind { Real, Fake }

/** Everything the app needs from an on-device model. All failures surface as [ModelException]. */
interface ModelBackend {
    val kind: BackendKind
    val media: MediaSupport
    /** True when the backend takes [GenerateRequest.turns] and a real system instruction instead of the transcript. */
    val nativeChat: Boolean get() = false
    /** True when the model declares tools and returns calls itself, instead of the JSON call format in the prompt. */
    val nativeTools: Boolean get() = false
    /** True when the model answers only while this app is on screen, as Gemini Nano does. */
    val foregroundOnly: Boolean get() = false
    /** True for backends that hold a large engine in this app's memory, so only one may be loaded at a time. */
    val heavy: Boolean get() = false
    /** Size of the one-time download, when known up front. */
    val downloadBytes: Long? get() = null

    suspend fun availability(): Availability
    fun download(): Flow<DownloadEvent>
    /** Removes downloaded files. No-op for system-managed models. */
    suspend fun delete() {}
    suspend fun warmup()
    /** Frees the engine's memory. The next request loads it again. */
    suspend fun unload() {}
    suspend fun tokenLimit(): Int
    /** Most input tokens one request may use, whatever [tokenLimit] says. */
    suspend fun inputTokenCap(): Int
    suspend fun supportsSystemPrompt(): Boolean
    suspend fun supportsPrefixCaching(): Boolean
    suspend fun countTokens(systemPrompt: String?, prompt: String): Int
    fun generate(request: GenerateRequest): Flow<GenerateChunk>
    suspend fun info(): BackendInfo
}
