package ai.tushar.nanochat.model

import ai.tushar.nanochat.data.AttachmentKind
import android.content.Context
import android.graphics.BitmapFactory
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Candidate
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.PromptPrefix
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull

/** Gemini Nano through ML Kit's Prompt API. */
class MlKitBackend(private val context: Context) : ModelBackend {
    private val client = Generation.getClient()
    override val kind = BackendKind.Real
    /** The Prompt API takes one image per request, with the text; it has no audio input. */
    override val media = MediaSupport(maxImages = 1, imagesInNewestTurnOnly = true, tokensPerImage = NanoImageTokens, audio = false)

    override suspend fun availability(): Availability = wrap {
        when (client.checkStatus()) {
            FeatureStatus.AVAILABLE -> Availability.Available
            FeatureStatus.DOWNLOADABLE -> Availability.Downloadable
            FeatureStatus.DOWNLOADING -> Availability.Downloading
            else -> Availability.Unavailable
        }
    }

    override fun download(): Flow<DownloadEvent> = client.download()
        .map { s ->
            when (s) {
                is DownloadStatus.DownloadStarted -> DownloadEvent.Started(s.bytesToDownload.takeIf { it > 0 })
                is DownloadStatus.DownloadProgress -> DownloadEvent.Progress(s.totalBytesDownloaded)
                DownloadStatus.DownloadCompleted -> DownloadEvent.Completed
                is DownloadStatus.DownloadFailed -> DownloadEvent.Failed(s.e.toModelException())
            }
        }
        .catch { e -> if (e is GenAiException) emit(DownloadEvent.Failed(e.toModelException())) else throw e }

    override suspend fun warmup() = wrap { client.warmup() }
    override suspend fun tokenLimit(): Int = wrap { client.getTokenLimit() }
    /** Max input the Prompt API accepts, whatever the model's total limit. */
    override suspend fun inputTokenCap(): Int = 4000
    override suspend fun supportsSystemPrompt(): Boolean = runCatching { client.isSystemPromptAvailable() }.getOrDefault(false)
    override suspend fun supportsPrefixCaching(): Boolean = runCatching { client.isCachingFeatureAvailable() }.getOrDefault(false)

    override suspend fun countTokens(systemPrompt: String?, prompt: String): Int = wrap {
        val req = if (systemPrompt != null) generateContentRequest(SystemInstruction(systemPrompt), TextPart(prompt)) {}
        else generateContentRequest(TextPart(prompt)) {}
        client.countTokens(req).totalTokens
    }

    override fun generate(request: GenerateRequest): Flow<GenerateChunk> {
        val configure: com.google.mlkit.genai.prompt.GenerateContentRequest.Builder.() -> Unit = {
            temperature = request.temperature
            topK = request.topK
            maxOutputTokens = request.maxOutputTokens
            request.promptPrefix?.let { promptPrefix = PromptPrefix(it) }
        }
        val image = request.turns.lastOrNull()?.media?.firstOrNull { it.kind == AttachmentKind.Image }
            ?.let { BitmapFactory.decodeFile(it.modelPath) }?.let(::ImagePart)
        val text = TextPart(request.prompt)
        val system = request.systemPrompt?.let(::SystemInstruction)
        val req = when {
            system != null && image != null -> generateContentRequest(system, image, text, configure)
            system != null -> generateContentRequest(system, text, configure)
            image != null -> generateContentRequest(image, text, configure)
            else -> generateContentRequest(text, configure)
        }
        return client.generateContentStream(req)
            .mapNotNull { chunk ->
                chunk.candidates.firstOrNull()?.let { GenerateChunk(it.text, it.finishReason?.let(::finishName)) }
            }
            .catch { e -> throw if (e is GenAiException) e.toModelException() else e }
    }

    override suspend fun info(): BackendInfo = BackendInfo(
        baseModelName = runCatching { client.getBaseModelName() }.getOrNull(),
        cachingAvailable = runCatching { client.isCachingFeatureAvailable() }.getOrDefault(false),
        aicoreVersion = runCatching { context.packageManager.getPackageInfo("com.google.android.aicore", 0).versionName }.getOrNull(),
    )

    private inline fun <T> wrap(block: () -> T): T = try {
        block()
    } catch (e: GenAiException) {
        throw e.toModelException()
    }

    private fun finishName(reason: Int): String = when (reason) {
        Candidate.FinishReason.STOP -> "STOP"
        Candidate.FinishReason.MAX_TOKENS -> "MAX_TOKENS"
        Candidate.FinishReason.OTHER -> "OTHER"
        else -> "UNKNOWN($reason)"
    }
}

/** Rough input cost of one image for Nano; ML Kit counts only text, so the prompt window reserves this much. */
private const val NanoImageTokens = 260

private fun GenAiException.toModelException(): ModelException {
    val code = errorCode
    val kind = when (code) {
        GenAiException.ErrorCode.BUSY -> ModelErrorKind.Busy
        GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED -> ModelErrorKind.Quota
        GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED -> ModelErrorKind.Background
        GenAiException.ErrorCode.REQUEST_TOO_LARGE -> ModelErrorKind.TooLarge
        GenAiException.ErrorCode.NOT_AVAILABLE, GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE,
        GenAiException.ErrorCode.AICORE_INCOMPATIBLE, GenAiException.ErrorCode.NOT_SUPPORTED -> ModelErrorKind.NotReady
        else -> ModelErrorKind.Other
    }
    val name = GenAiException.ErrorCode::class.java.fields
        .firstOrNull { runCatching { it.getInt(null) }.getOrNull() == code }?.name ?: "UNKNOWN"
    return ModelException(kind, code, name, message, retryDelay)
}
