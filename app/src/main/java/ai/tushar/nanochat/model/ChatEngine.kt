package ai.tushar.nanochat.model

import ai.tushar.nanochat.data.ChatDao
import ai.tushar.nanochat.data.Generation
import ai.tushar.nanochat.data.Message
import ai.tushar.nanochat.data.Role
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

/** A reply that is still being written. Lives only in memory; saved as a [Message] when done. */
data class PendingReply(val text: String) {
    val thinking: Boolean get() = text.isEmpty()
}

/** Something went wrong with the last reply, worded for the person chatting. */
data class ReplyProblem(val message: String, val canRetry: Boolean)

/** Max input the Prompt API accepts, whatever the model's total limit. */
const val InputTokenCap = 4000
private const val HistoryRows = 80
private const val CancelledByUser = "CANCELLED_BY_USER"

/**
 * Runs replies for every chat. Lives for the whole app, so a reply keeps going when you leave its chat.
 * The model handles one request at a time, so replies queue behind a lock.
 */
class ChatEngine(
    private val dao: ChatDao,
    private val model: NanoModel,
    private val settings: Settings,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private val jobs = mutableMapOf<Long, Job>()

    private val _pending = MutableStateFlow<Map<Long, PendingReply>>(emptyMap())
    val pending: StateFlow<Map<Long, PendingReply>> = _pending.asStateFlow()

    private val _problems = MutableStateFlow<Map<Long, ReplyProblem>>(emptyMap())
    val problems: StateFlow<Map<Long, ReplyProblem>> = _problems.asStateFlow()

    fun send(agentId: Long, text: String): Job? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || agentId in jobs) return null
        return start(agentId) { dao.insertMessage(Message(agentId = agentId, role = Role.User, text = trimmed, createdAt = now())) }
    }

    fun retry(agentId: Long): Job? = if (agentId in jobs) null else start(agentId) {}

    fun stop(agentId: Long) {
        jobs[agentId]?.cancel()
    }

    private fun start(agentId: Long, before: suspend () -> Unit): Job {
        _problems.update { it - agentId }
        _pending.update { it + (agentId to PendingReply("")) }
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
        job.start()
        return job
    }

    private suspend fun reply(agentId: Long) {
        val agent = dao.agentNow(agentId) ?: return
        val backend = model.backend
        val defaults = settings.defaultParamsNow()
        val limit = runCatching { model.tokenLimit() }.getOrNull()
        val params = GenerationParams(
            temperature = agent.temperature ?: defaults.temperature,
            topK = agent.topK ?: defaults.topK,
            maxOutputTokens = (agent.maxOutputTokens ?: defaults.maxOutputTokens).let { if (limit != null) it.coerceAtMost(limit / 2) else it },
        )
        val budget = minOf(InputTokenCap, (limit ?: Int.MAX_VALUE) - params.maxOutputTokens) - 8
        val history = dao.recentMessages(agentId, HistoryRows).map { Turn(it.role, it.text) }
        if (history.firstOrNull()?.role != Role.User) return

        val window = PromptWindow(agent.name, settings.displayName.first())
        val useSystem = backend.supportsSystemPrompt()
        val started = now()
        val clock = TimeSource.Monotonic.markNow()

        val plan = try {
            when (val r = window.build(agent.personality, history, budget, useSystem, backend::countTokens)) {
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
        var hitNextTurn = false
        val request = GenerateRequest(
            systemPrompt = plan.systemPrompt.takeIf { plan.useSystemInstruction },
            prompt = plan.prompt,
            temperature = params.temperature,
            topK = params.topK,
            maxOutputTokens = params.maxOutputTokens,
        )
        fun record() = Record(agentId, agent.name, out.toString(), firstTokenMs, clock.elapsedNow().inWholeMilliseconds, plan, params, finish, error, started, backend)
        try {
            backend.generate(request).takeWhile { !hitNextTurn }.collect { chunk ->
                if (chunk.text.isNotEmpty()) {
                    if (firstTokenMs == null) firstTokenMs = clock.elapsedNow().inWholeMilliseconds
                    out.append(chunk.text)
                    val shown = cleanReply(out.toString(), agent.name)
                    hitNextTurn = startsNextTurn(out.toString(), agent.name)
                    if (hitNextTurn) finish = "STOP (cut at next turn)"
                    _pending.update { it + (agentId to PendingReply(shown)) }
                }
                chunk.finishReason?.let { finish = it }
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

    private class Record(
        val agentId: Long, val agentName: String, val raw: String, val ttft: Long?, val totalMs: Long,
        val plan: PromptPlan, val params: GenerationParams, val finish: String?, val error: String?, val started: Long,
        val backend: ModelBackend,
    )

    private suspend fun save(r: Record) {
        val text = cleanReply(r.raw, r.agentName)
        val outputTokens = if (text.isEmpty()) 0 else runCatching { r.backend.countTokens(null, text) }.getOrDefault(0)
        val genMs = (r.totalMs - (r.ttft ?: r.totalMs)).coerceAtLeast(1)
        val tps = if (outputTokens > 1 && r.ttft != null) (outputTokens - 1) * 1000f / genMs else null
        if (text.isEmpty() && r.error == null && r.finish != CancelledByUser) {
            problem(r.agentId, ReplyProblem("${r.agentName} didn't have anything to say. Try asking another way.", canRetry = true))
        }
        if (text.isNotEmpty()) {
            dao.insertMessage(
                Message(agentId = r.agentId, role = Role.Agent, text = text, createdAt = now(), ttftMs = r.ttft, tokensPerSecond = tps, outputTokens = outputTokens),
            )
        }
        dao.insertGeneration(
            Generation(
                agentId = r.agentId, startedAt = r.started, backend = r.backend.kind.name,
                systemPrompt = r.plan.systemPrompt, prompt = r.plan.prompt, usedSystemInstruction = r.plan.useSystemInstruction,
                inputTokens = r.plan.inputTokens, inputBudget = r.plan.budget,
                includedTurns = r.plan.includedTurns, droppedTurns = r.plan.droppedTurns,
                temperature = r.params.temperature, topK = r.params.topK, maxOutputTokens = r.params.maxOutputTokens,
                ttftMs = r.ttft, totalMs = r.totalMs, outputTokens = outputTokens,
                finishReason = r.finish, error = r.error,
            ),
        )
    }

    private fun problem(agentId: Long, p: ReplyProblem) = _problems.update { it + (agentId to p) }

    private fun describe(e: ModelException, name: String): ReplyProblem = when (e.kind) {
        ModelErrorKind.Busy -> ReplyProblem("$name is busy right now. Give it a few seconds and try again.", canRetry = true)
        ModelErrorKind.Quota -> ReplyProblem("$name needs a rest. Android limits how much one app can use the model${waitHint(e.retryAfter)}.", canRetry = true)
        ModelErrorKind.Background -> ReplyProblem("$name only answers while the app is open on screen.", canRetry = true)
        ModelErrorKind.TooLarge -> ReplyProblem("That message is too long for $name. Try a shorter one.", canRetry = false)
        ModelErrorKind.NotReady -> ReplyProblem("The model isn't ready on this phone yet. Check Google AICore for updates.", canRetry = true)
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
