package ai.tushar.nanochat.model

import ai.tushar.nanochat.attachments.AttachmentStore
import ai.tushar.nanochat.attachments.DraftAttachment
import ai.tushar.nanochat.data.Agent
import ai.tushar.nanochat.data.AttachmentKind
import ai.tushar.nanochat.data.ChatDao
import ai.tushar.nanochat.data.Generation
import ai.tushar.nanochat.data.Message
import ai.tushar.nanochat.data.Role
import ai.tushar.nanochat.data.ToolStep
import ai.tushar.nanochat.handset.HandsetLink
import ai.tushar.nanochat.handset.McpException
import ai.tushar.nanochat.handset.ModelOutput
import ai.tushar.nanochat.handset.PhoneSession
import ai.tushar.nanochat.handset.StepOutcome
import ai.tushar.nanochat.handset.ToolRequest
import ai.tushar.nanochat.handset.callClosed
import ai.tushar.nanochat.handset.checkCall
import ai.tushar.nanochat.handset.fitToBudget
import ai.tushar.nanochat.handset.isRefusal
import ai.tushar.nanochat.handset.mightBeCall
import ai.tushar.nanochat.handset.parseModelOutput
import ai.tushar.nanochat.handset.refusalForPerson
import ai.tushar.nanochat.handset.stepLabel
import ai.tushar.nanochat.settings.GenerationParams
import ai.tushar.nanochat.settings.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import kotlin.time.TimeSource

/** A tool step while the reply is still being written. */
data class PendingStep(val label: String, val running: Boolean = false, val outcome: StepOutcome = StepOutcome.Done)

/** A reply that is still being written. Lives only in memory; saved as a [Message] when done. */
data class PendingReply(val text: String, val steps: List<PendingStep> = emptyList()) {
    val thinking: Boolean get() = text.isEmpty()
    val usingPhone: Boolean get() = steps.any { it.running }
}

private fun pendingStep(s: ToolStep) = PendingStep(s.label, outcome = StepOutcome.valueOf(s.outcome))

private const val UsingPhone = "Using your phone…"
/** Share of the input budget kept for tool rounds, and its cap. */
private const val ToolShare = 0.4f
private const val MaxToolReserve = 1200
/** Room kept for the model's next call when sizing a result. */
private const val CallReserve = 60
private const val MinResultTokens = 32

private suspend fun countOrEstimate(backend: ModelBackend, text: String): Int =
    runCatching { backend.countTokens(null, text) }.getOrElse { estimateTokens(text) }

/** Something went wrong with the last reply, worded for the person chatting. */
data class ReplyProblem(val message: String, val canRetry: Boolean)

private const val HistoryRows = 80
private const val CancelledByUser = "CANCELLED_BY_USER"

/**
 * Runs replies for every chat. Lives for the whole app, so a reply keeps going when you leave its chat.
 * The model handles one request at a time, so replies queue behind a lock.
 */
class ChatEngine(
    private val dao: ChatDao,
    private val models: Models,
    private val settings: Settings,
    private val store: AttachmentStore,
    private val scope: CoroutineScope,
    /** Handset, for companions with "Use this phone" on. Null in tests. */
    private val handset: HandsetLink? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private val jobs = mutableMapOf<Long, Job>()

    private val _pending = MutableStateFlow<Map<Long, PendingReply>>(emptyMap())
    val pending: StateFlow<Map<Long, PendingReply>> = _pending.asStateFlow()

    private val _problems = MutableStateFlow<Map<Long, ReplyProblem>>(emptyMap())
    val problems: StateFlow<Map<Long, ReplyProblem>> = _problems.asStateFlow()

    /** Sends a message with optional photos or voice notes. A message may be media only. */
    fun send(agentId: Long, text: String, attachments: List<DraftAttachment> = emptyList()): Job? {
        val trimmed = text.trim()
        if ((trimmed.isEmpty() && attachments.isEmpty()) || agentId in jobs) return null
        return start(agentId) {
            val id = dao.insertMessage(Message(agentId = agentId, role = Role.User, text = trimmed, createdAt = now()))
            if (attachments.isNotEmpty()) dao.insertAttachments(attachments.map { it.toAttachment(id) })
        }
    }

    fun retry(agentId: Long): Job? = if (agentId in jobs) null else start(agentId) {}

    /**
     * Run once at app start. A chat whose last message is the user's lost its reply when the app was closed or killed,
     * so it gets the same "Try again" as a reply that failed partway. A partial reply was never saved, so it's dropped.
     */
    suspend fun recoverUnanswered() {
        for (agent in dao.agentsAwaitingReply()) {
            val p = ReplyProblem("${agent.name} didn't get to answer before the app closed. Try again?", canRetry = true)
            synchronized(jobs) { if (agent.id !in jobs) problem(agent.id, p) }
        }
    }

    fun stop(agentId: Long) {
        jobs[agentId]?.cancel()
    }

    private fun start(agentId: Long, before: suspend () -> Unit): Job {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                before()
                lock.withLock { reply(agentId) }
            } finally {
                _pending.update { it - agentId }
                synchronized(jobs) { jobs.remove(agentId) }
            }
        }
        synchronized(jobs) { jobs[agentId] = job }
        _problems.update { it - agentId }
        _pending.update { it + (agentId to PendingReply("")) }
        job.start()
        return job
    }

    private suspend fun reply(agentId: Long) {
        val agent = dao.agentNow(agentId) ?: return
        val modelId = ModelId.parse(agent.model) ?: settings.defaultModelNow()
        models.use(modelId) { backend -> reply(agent, modelId, backend) }
    }

    private suspend fun reply(agent: Agent, modelId: ModelId, backend: ModelBackend) {
        val agentId = agent.id
        val defaults = settings.defaultParamsNow()
        val limit = runCatching { backend.tokenLimit() }.getOrNull()
        val cap = runCatching { backend.inputTokenCap() }.getOrDefault(limit ?: 4000)
        val params = GenerationParams(
            temperature = agent.temperature ?: defaults.temperature,
            topK = agent.topK ?: defaults.topK,
            maxOutputTokens = (agent.maxOutputTokens ?: defaults.maxOutputTokens).let { if (limit != null) it.coerceAtMost(limit / 2) else it },
        )
        val budget = minOf(cap, (limit ?: Int.MAX_VALUE) - params.maxOutputTokens) - 8
        val media = dao.attachmentsNow(agentId).groupBy { it.messageId }
        val history = dao.recentMessages(agentId, HistoryRows).map { m ->
            Turn(m.role, m.text, media[m.id].orEmpty().map { TurnMedia(it.kind, store.file(it.modelFile).absolutePath, it.durationMs) })
        }
        if (history.firstOrNull()?.role != Role.User) return

        val window = PromptWindow(agent.name, settings.displayName.first())
        val useSystem = backend.supportsSystemPrompt()
        val useCache = !useSystem && backend.supportsPrefixCaching()
        val started = now()
        val clock = TimeSource.Monotonic.markNow()
        val loadedBefore = backend.info().loadMs

        // Steps saved under the reply. A phone that can't be reached is one failed step, and the reply goes on without tools.
        val steps = mutableListOf<ToolStep>()
        val phone = if (agent.useHandset) openPhone(agent, steps) else null
        val native = phone != null && backend.nativeTools
        val specs = if (native) toolSpecs(phone!!.schemas) else emptyList()
        val personality = if (phone == null) agent.personality else agent.personality.trim() + "\n\n" + toolInstructions(phone.schemas.keys, native)
        val declared = if (native) runCatching { backend.countTokens(null, specs.joinToString("\n") { "${it.name} ${it.description} ${it.parameters}" }) }.getOrDefault(0) else 0
        // Tool rounds need room after the history: they get up to 40% of the input.
        val reserve = if (phone != null) (budget * ToolShare).toInt().coerceAtMost(MaxToolReserve) else 0
        if (steps.isNotEmpty()) _pending.update { it + (agentId to PendingReply("", steps.map(::pendingStep))) }

        val plan = try {
            when (val r = window.build(personality, history, budget - reserve - declared, useSystem, backend::countTokens, backend.media)) {
                is PromptResult.Fits -> r.plan
                is PromptResult.TooLong -> return problem(agentId, ReplyProblem("That message is too long for ${agent.name}. Try a shorter one.", canRetry = false))
            }
        } catch (e: ModelException) {
            return problem(agentId, describe(e, agent.name))
        }

        val out = StringBuilder()
        var firstTokenMs: Long? = null
        var finish: String? = null
        var error: String? = null
        var stats: RunStats? = null
        // ML Kit rejects a cached prefix on requests that carry an image.
        val sendsImage = plan.turns.lastOrNull()?.media?.any { it.kind == AttachmentKind.Image } == true
        val prefix = window.instructions(plan.systemPrompt).takeIf { useCache && !sendsImage && plan.prompt.startsWith(it) }
        var request = GenerateRequest(
            systemPrompt = plan.systemPrompt.takeIf { plan.useSystemInstruction },
            prompt = if (prefix != null) plan.prompt.removePrefix(prefix) else plan.prompt,
            promptPrefix = prefix,
            temperature = params.temperature,
            topK = params.topK,
            maxOutputTokens = params.maxOutputTokens,
            turns = plan.turns,
            chatKey = agentId,
            tools = specs,
        )
        var used = plan.inputTokens + declared
        // Set when the reply is a fixed line instead of the model's words, as when it kept getting the call format wrong.
        var fallback: String? = null
        suspend fun record(): Record {
            val info = runCatching { backend.info() }.getOrNull()
            val sent = plan.copy(prompt = (prefix ?: "") + request.prompt)
            // A call cut off by Stop isn't a reply.
            val text = fallback ?: out.toString().takeUnless { phone != null && !native && (callClosed(it) || it.trimStart().startsWith("{")) }.orEmpty()
            return Record(
                agentId, agent.name, text, firstTokenMs, clock.elapsedNow().inWholeMilliseconds, sent, params, finish, error, started, backend,
                prefix != null, modelId, info?.accelerator, info?.loadMs?.takeIf { it != loadedBefore }, stats, steps.toList(),
            )
        }
        fun show(text: String, running: Boolean = false) {
            val shown = steps.map(::pendingStep) + listOfNotNull(if (running) PendingStep(UsingPhone, running = true) else null)
            _pending.update { it + (agentId to PendingReply(text, shown)) }
        }
        try {
            var retried = false
            while (true) {
                out.clear()
                var hitNextTurn = false
                var callDone = false
                val calls = mutableListOf<ToolRequest>()
                // A reply that starts like a call stays hidden, and stops once its JSON closes.
                val hideCalls = phone != null && !native
                backend.generate(request).takeWhile { !hitNextTurn && !callDone }.collect { chunk ->
                    if (chunk.text.isNotEmpty()) {
                        if (firstTokenMs == null) firstTokenMs = clock.elapsedNow().inWholeMilliseconds
                        out.append(chunk.text)
                        val raw = out.toString()
                        hitNextTurn = startsNextTurn(raw, agent.name)
                        if (hitNextTurn) finish = "STOP (cut at next turn)"
                        if (hideCalls && mightBeCall(raw)) {
                            callDone = callClosed(raw)
                            show("")
                        } else {
                            show(cleanReply(raw, agent.name))
                        }
                    }
                    calls += chunk.toolCalls
                    chunk.finishReason?.let { finish = it }
                    chunk.stats?.let { stats = it }
                }
                if (phone == null) break
                val said = cleanReply(out.toString(), agent.name)
                val decision = when {
                    calls.isNotEmpty() -> checkCall(calls.first(), phone.schemas)
                    native -> ModelOutput.Answer(said)
                    else -> parseModelOutput(said, phone.schemas)
                }
                when (decision) {
                    is ModelOutput.Answer -> break
                    is ModelOutput.Invalid -> {
                        if (retried) {
                            fallback = "Sorry, I couldn't work out how to do that on your phone. Could you ask another way?"
                            break
                        }
                        retried = true
                        val message = retryMessage(decision.reason)
                        request = request.withRound(said, calls.firstOrNull(), message, calls.firstOrNull()?.tool, agent.name)
                        used += countOrEstimate(backend, said + message)
                    }
                    is ModelOutput.Call -> {
                        if (steps.count { it.tool.isNotEmpty() } >= MaxToolCalls) {
                            fallback = "I've used my $MaxToolCalls phone steps for this reply. Tap a step to see what came back."
                            break
                        }
                        show("", running = true)
                        val call = decision.request
                        val ran = runTool(phone, call, agent)
                        val callsLeft = MaxToolCalls - steps.size
                        val share = (budget - used - CallReserve) / minOf(2, callsLeft).coerceAtLeast(1)
                        val fitted = fitToBudget(ran.forModel, share.coerceAtLeast(MinResultTokens)) { countOrEstimate(backend, it) }
                        steps += ToolStep(
                            messageId = 0, position = steps.size, tool = call.tool, arguments = call.arguments.toString(),
                            label = stepLabel(call.tool, call.arguments, ran.outcome), outcome = ran.outcome.name,
                            result = ran.forPerson.take(ToolStep.MaxResultChars),
                            sentChars = if (ran.outcome == StepOutcome.Done && fitted.cut) fitted.keptChars else ran.forPerson.length,
                        )
                        val last = steps.size >= MaxToolCalls
                        val message = resultMessage(call.tool, fitted.text, native, last)
                        // Native chat replays the model's own call, so its cache still matches; the JSON format gets the call cleaned up.
                        val modelSaid = if (native) said else callJson(call)
                        request = request.withRound(modelSaid, calls.firstOrNull() ?: call, message, call.tool, agent.name)
                        used += countOrEstimate(backend, modelSaid + message)
                        show("")
                    }
                }
            }
        } catch (e: CancellationException) {
            finish = CancelledByUser
            withContext(NonCancellable) { save(record()) }
            throw e
        } catch (e: ModelException) {
            error = "${e.codeName} (${e.code}): ${e.message}"
            problem(agentId, describe(e, agent.name))
            out.clear()
        } catch (e: Exception) {
            error = "${e::class.simpleName}: ${e.message}"
            problem(agentId, ReplyProblem("${agent.name} got stuck on that one. Try again?", canRetry = true))
            out.clear()
        }
        save(record())
    }

    /** Handset's tools for this reply, or null with a failed step saying why the phone can't be used. */
    private suspend fun openPhone(agent: Agent, steps: MutableList<ToolStep>): PhoneSession? {
        val link = handset ?: return null
        fun failed(message: String): PhoneSession? {
            steps += ToolStep(messageId = 0, position = 0, tool = "", arguments = "{}", label = "Couldn't use your phone", outcome = StepOutcome.Failed.name, result = message, sentChars = message.length)
            return null
        }
        if (link.connection.value == null) return failed("Nano Chat isn't connected to Handset. Turn on Use this phone in Edit ${agent.name} to connect.")
        return try {
            link.session().takeIf { it.schemas.isNotEmpty() } ?: failed("Handset didn't offer any tools Nano Chat uses. Update Handset, then try again.")
        } catch (e: McpException) {
            failed(phoneProblem(e, agent))
        }
    }

    private class ToolRun(val outcome: StepOutcome, val forModel: String, val forPerson: String)

    private suspend fun runTool(phone: PhoneSession, call: ToolRequest, agent: Agent): ToolRun = try {
        val r = phone.client.call(call.tool, call.arguments)
        when {
            !r.isError -> ToolRun(StepOutcome.Done, r.text, r.text)
            isRefusal(r.text) -> ToolRun(StepOutcome.Refused, "Error: ${r.text}", refusalForPerson(r.text))
            else -> ToolRun(StepOutcome.Failed, "Error: ${r.text}", r.text)
        }
    } catch (e: McpException) {
        val message = phoneProblem(e, agent)
        ToolRun(StepOutcome.Failed, "Error: the phone couldn't be reached. Tell the user: $message", message)
    }

    private fun phoneProblem(e: McpException, agent: Agent): String = when (e.kind) {
        McpException.Kind.Unauthorized -> {
            handset?.forget()
            "Handset no longer accepts Nano Chat. Turn on Use this phone in Edit ${agent.name} to connect again."
        }
        McpException.Kind.Unreachable -> "Handset isn't answering. Open Handset and check that Direct connection is on, under Connections in its Settings."
        McpException.Kind.Protocol -> "Handset answered with something Nano Chat can't read: ${e.message}"
    }

    private class Record(
        val agentId: Long, val agentName: String, val raw: String, val ttft: Long?, val totalMs: Long,
        val plan: PromptPlan, val params: GenerationParams, val finish: String?, val error: String?, val started: Long,
        val backend: ModelBackend,
        val usedPrefixCache: Boolean,
        val model: ModelId,
        val accelerator: String?,
        val loadMs: Long?,
        val stats: RunStats?,
        val steps: List<ToolStep>,
    )

    private suspend fun save(r: Record) {
        val text = cleanReply(r.raw, r.agentName)
        val outputTokens = if (text.isEmpty()) 0 else runCatching { r.backend.countTokens(null, text) }.getOrDefault(0)
        val genMs = (r.totalMs - (r.ttft ?: r.totalMs)).coerceAtLeast(1)
        val tps = r.stats?.decodeTokensPerSecond ?: if (outputTokens > 1 && r.ttft != null) (outputTokens - 1) * 1000f / genMs else null
        // A reply that only ran tools still shows its steps, under the last one's line.
        val shownText = text.ifEmpty { r.steps.lastOrNull()?.takeIf { r.error == null }?.let { "${it.label}." }.orEmpty() }
        if (shownText.isEmpty() && r.error == null && r.finish != CancelledByUser) {
            problem(r.agentId, ReplyProblem("${r.agentName} didn't have anything to say. Try asking another way.", canRetry = true))
        }
        if (shownText.isNotEmpty()) {
            dao.insertReply(
                Message(agentId = r.agentId, role = Role.Agent, text = shownText, createdAt = now(), ttftMs = r.ttft, tokensPerSecond = tps, outputTokens = outputTokens,
                    model = r.model.name, accelerator = r.accelerator,
                ),
                r.steps,
            )
        }
        dao.insertGeneration(
            Generation(
                agentId = r.agentId, startedAt = r.started, backend = r.backend.kind.name,
                systemPrompt = r.plan.systemPrompt, prompt = r.plan.prompt, usedSystemInstruction = r.plan.useSystemInstruction, usedPrefixCache = r.usedPrefixCache,
                inputTokens = r.plan.inputTokens, inputBudget = r.plan.budget,
                includedTurns = r.plan.includedTurns, droppedTurns = r.plan.droppedTurns,
                temperature = r.params.temperature, topK = r.params.topK, maxOutputTokens = r.params.maxOutputTokens,
                ttftMs = r.ttft, totalMs = r.totalMs, outputTokens = outputTokens,
                finishReason = r.finish, error = r.error,
                model = r.model.name, accelerator = r.accelerator, loadMs = r.loadMs,
                prefillTokensPerSecond = r.stats?.prefillTokensPerSecond, decodeTokensPerSecond = r.stats?.decodeTokensPerSecond,
                imagesSent = r.plan.mediaKept.images, imagesDropped = r.plan.mediaDropped.images,
                audioSent = r.plan.mediaKept.audio, audioDropped = r.plan.mediaDropped.audio,
            ),
        )
    }

    private fun problem(agentId: Long, p: ReplyProblem) = _problems.update { it + (agentId to p) }

    private fun describe(e: ModelException, name: String): ReplyProblem = when (e.kind) {
        ModelErrorKind.Busy -> ReplyProblem("$name is busy right now. Give it a few seconds and try again.", canRetry = true)
        ModelErrorKind.Quota -> ReplyProblem("$name needs a rest. Android limits how much one app can use the model${waitHint(e.retryAfter)}.", canRetry = true)
        ModelErrorKind.Background -> ReplyProblem("$name only answers while the app is open on screen.", canRetry = true)
        ModelErrorKind.TooLarge -> ReplyProblem("That message is too long for $name. Try a shorter one.", canRetry = false)
        ModelErrorKind.NotReady -> ReplyProblem("$name's model isn't ready yet. Try again in a moment.", canRetry = true)
        ModelErrorKind.Other -> ReplyProblem("$name got stuck on that one. Try again?", canRetry = true)
    }

    private fun waitHint(d: Duration): String {
        val mins = d.toMinutes()
        return when {
            d.isZero -> ""
            mins >= 60 -> ", so try again in about ${mins / 60} h"
            mins >= 1 -> ", so try again in about $mins min"
            else -> ", so try again in a moment"
        }
    }
}

private fun turnMarker(agentName: String) = Regex("\\n\\s*(User|${Regex.escape(agentName)}):")

private fun startsNextTurn(raw: String, agentName: String) = turnMarker(agentName).containsMatchIn(raw.trimStart().removePrefix("$agentName:"))

/** Trims whitespace, a leading "Name:" the model sometimes echoes, and any made-up next turn. */
internal fun cleanReply(raw: String, agentName: String): String {
    var t = raw.trimStart()
    if (t.startsWith("$agentName:")) t = t.removePrefix("$agentName:").trimStart()
    turnMarker(agentName).find(t)?.let { t = t.substring(0, it.range.first) }
    return t.trimEnd()
}
