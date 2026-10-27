package ai.tushar.nanochat.litert

import ai.tushar.nanochat.data.AttachmentKind
import ai.tushar.nanochat.data.Role
import ai.tushar.nanochat.model.Availability
import ai.tushar.nanochat.model.BackendInfo
import ai.tushar.nanochat.model.BackendKind
import ai.tushar.nanochat.model.DownloadEvent
import ai.tushar.nanochat.model.GenerateChunk
import ai.tushar.nanochat.model.GenerateRequest
import ai.tushar.nanochat.model.MediaSupport
import ai.tushar.nanochat.model.ModelBackend
import ai.tushar.nanochat.model.ModelErrorKind
import ai.tushar.nanochat.model.ModelException
import ai.tushar.nanochat.model.RunStats
import ai.tushar.nanochat.model.ToolSpec
import ai.tushar.nanochat.model.ToolTurn
import ai.tushar.nanochat.model.Turn
import ai.tushar.nanochat.handset.ToolRequest
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.ToolCall
import com.google.ai.edge.litertlm.tool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.TimeSource
import com.google.ai.edge.litertlm.ModelInfo as LiteRtModelInfo

private const val Tag = "LiteRtBackend"
private const val TopP = 0.95

/**
 * A Gemma model run in this app's process through Google's LiteRT-LM. The model file is downloaded in-app (or pushed
 * with adb) to the app's external files dir. The engine loads on first use, on the GPU when it can and the CPU when not.
 *
 * Each chat keeps one live conversation, so the model's cache already holds the earlier turns and a new message only
 * prefills itself. When the app's history no longer matches (an edit, a dropped turn, another chat), the conversation
 * is rebuilt from the app's turns.
 */
class LiteRtBackend(context: Context, private val spec: LiteRtSpec) : ModelBackend {
    override val kind = BackendKind.Real
    override val nativeChat = true
    /** Gemma 4's chat template has tool declarations, calls and responses; LiteRT-LM hands the calls back unrun. */
    override val nativeTools = true
    override val heavy = true
    override val downloadBytes = spec.bytes
    override val media = MediaSupport(
        maxImages = spec.maxImages,
        imagesInNewestTurnOnly = false,
        tokensPerImage = spec.visualTokenBudget,
        audio = true,
        audioTokensPerSecond = spec.audioTokensPerSecond,
    )

    private val dir = File(context.getExternalFilesDir(null), "models")
    private val modelFile = File(dir, spec.file)
    private val cacheDir = File(context.cacheDir, "litert").apply { mkdirs() }
    private val lock = Mutex()

    private var engine: Engine? = null
    private var accelerator: String? = null
    private var loadMs: Long? = null
    private var speculative: Boolean? = null

    private var conversation: Conversation? = null
    private var conversationKey: ConversationKey? = null
    /** What the live conversation holds, oldest first, including the model's own replies. */
    private var conversationTurns: List<Turn> = emptyList()

    private data class ConversationKey(
        val chat: Long, val system: String?, val temperature: Float, val topK: Int, val maxOutput: Int, val tools: List<ToolSpec>,
    )

    private fun complete(): Boolean = modelFile.isFile && modelFile.length() == spec.bytes

    override suspend fun availability(): Availability = if (complete()) Availability.Available else Availability.Downloadable

    override fun download(): Flow<DownloadEvent> = downloadFile(spec.url, modelFile, spec.bytes)

    override suspend fun delete() {
        unload()
        modelFile.delete()
        File(modelFile.path + ".part").delete()
        cacheDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    override suspend fun warmup() {
        lock.withLock { ensureEngine() }
    }

    override suspend fun unload() = lock.withLock {
        closeConversation()
        val e = engine ?: return@withLock
        runCatching { e.close() }
        engine = null
        accelerator = null
        Log.i(Tag, "Unloaded ${spec.file}")
    }

    override suspend fun tokenLimit(): Int = spec.maxTokens
    override suspend fun inputTokenCap(): Int = spec.maxTokens
    override suspend fun supportsSystemPrompt(): Boolean = true
    override suspend fun supportsPrefixCaching(): Boolean = false

    /** LiteRT-LM has no tokenizer call; Gemma averages about 4 characters per English token, so this errs high. */
    override suspend fun countTokens(systemPrompt: String?, prompt: String): Int =
        ((systemPrompt?.length ?: 0) + prompt.length + 2) / 3 + 8

    @OptIn(ExperimentalApi::class)
    override fun generate(request: GenerateRequest): Flow<GenerateChunk> = flow {
        lock.withLock {
            val e = ensureEngine()
            val turns = request.turns
            require(turns.isNotEmpty() && turns.last().role == Role.User) { "turns must end with the user's message" }
            val key = ConversationKey(request.chatKey, request.systemPrompt, request.temperature, request.topK, request.maxOutputTokens, request.tools)
            val history = turns.dropLast(1)
            val conv = conversation.takeIf { conversationKey == key && sameTurns(conversationTurns, history) }
                ?: openConversation(e, key, request, history)
            val reply = StringBuilder()
            val calls = mutableListOf<ToolRequest>()
            try {
                conv.sendMessageAsync(messageOf(turns.last())).collect { m ->
                    val text = m.toString()
                    if (text.isNotEmpty()) {
                        reply.append(text)
                        emit(GenerateChunk(text, null))
                    }
                    if (m.toolCalls.isNotEmpty()) {
                        val requests = m.toolCalls.map { ToolRequest(it.name, toJson(it.arguments)) }
                        calls += requests
                        emit(GenerateChunk("", null, toolCalls = requests))
                    }
                }
            } catch (c: CancellationException) {
                // The native side doesn't roll its cache back on cancel, so this conversation can't be reused.
                withContext(NonCancellable) { runCatching { conv.cancelProcess() }; closeConversation() }
                throw c
            } catch (t: Throwable) {
                closeConversation()
                throw ModelException(ModelErrorKind.Other, null, "LITERT_ERROR", t.message)
            }
            conversationTurns = turns + Turn(Role.Agent, reply.toString().trim(), tool = calls.firstOrNull()?.let(ToolTurn::Call))
            val stats = runCatching { conv.getBenchmarkInfo() }.getOrNull()?.let {
                RunStats(
                    prefillTokensPerSecond = it.lastPrefillTokensPerSecond.toFloat().takeIf { v -> v > 0 },
                    decodeTokensPerSecond = it.lastDecodeTokensPerSecond.toFloat().takeIf { v -> v > 0 },
                    prefillTokens = it.lastPrefillTokenCount,
                    decodeTokens = it.lastDecodeTokenCount,
                )
            }
            emit(GenerateChunk("", if ((stats?.decodeTokens ?: 0) >= request.maxOutputTokens) "MAX_TOKENS" else "STOP", stats))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun info(): BackendInfo = BackendInfo(
        baseModelName = spec.file,
        cachingAvailable = false,
        accelerator = accelerator,
        loadMs = loadMs,
        speculativeDecoding = speculative,
        fileBytes = modelFile.takeIf { it.isFile }?.length(),
    )

    private fun openConversation(e: Engine, key: ConversationKey, request: GenerateRequest, history: List<Turn>): Conversation {
        closeConversation()
        val conv = e.createConversation(
            ConversationConfig(
                systemInstruction = request.systemPrompt?.let { Contents.of(it) },
                initialMessages = history.map(::messageOf),
                samplerConfig = SamplerConfig(topK = request.topK, topP = TopP, temperature = request.temperature.toDouble()),
                maxOutputToken = request.maxOutputTokens,
                // Chat replies should start right away; Gemma 4's thinking mode would add a long silent pause.
                thinkingConfig = ThinkingConfig(enableThinking = false),
                tools = request.tools.map { tool(DeclaredTool(it)) },
                // The app runs each call through Handset itself, so it can show and limit them.
                automaticToolCalling = false,
            ),
        )
        conversation = conv
        conversationKey = key
        conversationTurns = history
        return conv
    }

    private fun closeConversation() {
        conversation?.let { runCatching { it.close() } }
        conversation = null
        conversationKey = null
        conversationTurns = emptyList()
    }

    /** Loads the engine if needed: GPU first, then CPU. Runs under [lock]. */
    @OptIn(ExperimentalApi::class)
    private suspend fun ensureEngine(): Engine = engine ?: withContext(Dispatchers.IO) {
        if (!complete()) throw ModelException(ModelErrorKind.NotReady, null, "NOT_DOWNLOADED", "${spec.file} isn't downloaded yet")
        val canSpeculate = runCatching {
            LiteRtModelInfo.from(modelFile.path).use { (it as? LiteRtModelInfo.Llm)?.hasSpeculativeDecodingSupport() == true }
        }.getOrDefault(false)
        val attempts = listOf(
            Triple("GPU", Backend.GPU(), Backend.GPU()),
            Triple("CPU", Backend.CPU(), Backend.CPU()),
        )
        var lastError: Throwable? = null
        for ((name, main, vision) in attempts) {
            val mark = TimeSource.Monotonic.markNow()
            val e = Engine(
                EngineConfig(
                    modelPath = modelFile.path,
                    backend = main,
                    visionBackend = vision,
                    audioBackend = Backend.CPU(),
                    maxNumTokens = spec.maxTokens,
                    maxNumImages = spec.maxImages,
                    cacheDir = cacheDir.path,
                ),
            )
            try {
                ExperimentalFlags.enableBenchmark = true
                ExperimentalFlags.enableSpeculativeDecoding = canSpeculate
                ExperimentalFlags.visualTokenBudget = spec.visualTokenBudget
                e.initialize()
                engine = e
                accelerator = name
                speculative = canSpeculate
                loadMs = mark.elapsedNow().inWholeMilliseconds
                Log.i(Tag, "Loaded ${spec.file} on $name in $loadMs ms, speculative decoding $canSpeculate")
                return@withContext e
            } catch (t: Throwable) {
                Log.w(Tag, "Loading ${spec.file} on $name failed", t)
                lastError = t
            } finally {
                ExperimentalFlags.enableBenchmark = false
                ExperimentalFlags.enableSpeculativeDecoding = null
            }
        }
        throw ModelException(ModelErrorKind.NotReady, null, "LOAD_FAILED", lastError?.message)
    }
}

/** A turn as LiteRT-LM's message: a tool's result, the model's call, or plain text and media. */
private fun messageOf(turn: Turn): Message = when (val tool = turn.tool) {
    is ToolTurn.Result -> Message.tool(Contents.of(listOf(Content.ToolResponse(tool.tool, turn.text))))
    is ToolTurn.Call -> Message.model(
        if (turn.text.isBlank()) Contents.of(emptyList()) else Contents.of(turn.text),
        toolCalls = listOf(ToolCall(tool.request.tool, fromJson(tool.request.arguments))),
    )
    null -> if (turn.role == Role.User) Message.user(contentsOf(turn)) else Message.model(turn.text)
}

/** A tool declared to the model. [execute] never runs: automatic tool calling is off. */
private class DeclaredTool(private val spec: ToolSpec) : OpenApiTool {
    override fun getToolDescriptionJsonString(): String = buildJsonObject {
        put("name", spec.name)
        put("description", spec.description)
        put("parameters", spec.parameters)
    }.toString()

    override fun execute(paramsJsonString: String): String = error("Tools run through Handset, not LiteRT-LM")
}

private fun toJson(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toJson(v) })
    is Iterable<*> -> JsonArray(value.map(::toJson))
    else -> JsonPrimitive(value.toString())
}

private fun toJson(arguments: Map<String, Any?>): JsonObject = JsonObject(arguments.mapValues { toJson(it.value) })

private fun fromJson(e: JsonElement): Any? = when (e) {
    JsonNull -> null
    is JsonPrimitive -> if (e.isString) e.content else e.booleanOrNull ?: e.longOrNull ?: e.doubleOrNull ?: e.content
    is JsonObject -> e.mapValues { fromJson(it.value) }
    is JsonArray -> e.map(::fromJson)
}

private fun fromJson(o: JsonObject): Map<String, Any?> = o.mapValues { fromJson(it.value) }

/** Photos and voice notes first, then the text, as the Gemma chat template expects. */
private fun contentsOf(turn: Turn): Contents {
    val parts = buildList {
        turn.media.filter { it.kind == AttachmentKind.Image }.forEach { add(Content.ImageFile(it.modelPath)) }
        turn.media.filter { it.kind == AttachmentKind.Audio }.forEach { add(Content.AudioFile(it.modelPath)) }
        if (turn.text.isNotBlank() || isEmpty()) add(Content.Text(turn.text.trim()))
    }
    return Contents.of(parts)
}

private fun sameTurns(a: List<Turn>, b: List<Turn>): Boolean =
    a.size == b.size && a.zip(b).all { (x, y) -> x.role == y.role && x.text.trim() == y.text.trim() && x.media == y.media && x.tool == y.tool }
