package ai.tushar.nanochat.model

import kotlinx.coroutines.flow.Flow
import java.time.Duration

enum class Availability { Unavailable, Downloadable, Downloading, Available }

sealed interface DownloadEvent {
    data class Started(val totalBytes: Long?) : DownloadEvent
    data class Progress(val downloadedBytes: Long) : DownloadEvent
    data object Completed : DownloadEvent
    data class Failed(val error: ModelException) : DownloadEvent
}

data class GenerateRequest(
    val systemPrompt: String?,
    val prompt: String,
    val temperature: Float,
    val topK: Int,
    val maxOutputTokens: Int,
)

/** One streamed piece of a reply. [finishReason] is set on the last chunk only. */
data class GenerateChunk(val text: String, val finishReason: String?)

enum class ModelErrorKind { Busy, Quota, Background, TooLarge, NotReady, Other }

class ModelException(
    val kind: ModelErrorKind,
    val code: Int?,
    val codeName: String,
    message: String?,
    val retryAfter: Duration = Duration.ZERO,
) : Exception(message)

data class BackendInfo(val baseModelName: String?, val cachingAvailable: Boolean, val aicoreVersion: String?)

enum class BackendKind(val label: String) { Real("Gemini Nano (ML Kit)"), Fake("Fake model") }

/** Everything the app needs from an on-device model. All failures surface as [ModelException]. */
interface ModelBackend {
    val kind: BackendKind
    suspend fun availability(): Availability
    fun download(): Flow<DownloadEvent>
    suspend fun warmup()
    suspend fun tokenLimit(): Int
    suspend fun supportsSystemPrompt(): Boolean
    suspend fun countTokens(systemPrompt: String?, prompt: String): Int
    fun generate(request: GenerateRequest): Flow<GenerateChunk>
    suspend fun info(): BackendInfo
}
