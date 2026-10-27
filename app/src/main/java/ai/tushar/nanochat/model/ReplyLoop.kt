package ai.tushar.nanochat.model

import ai.tushar.nanochat.data.ToolStep
import ai.tushar.nanochat.handset.ModelOutput
import ai.tushar.nanochat.handset.PhoneTool
import ai.tushar.nanochat.handset.StepOutcome
import ai.tushar.nanochat.handset.ToolRequest
import ai.tushar.nanochat.handset.callClosed
import ai.tushar.nanochat.handset.checkCall
import ai.tushar.nanochat.handset.fitToBudget
import ai.tushar.nanochat.handset.mightBeCall
import ai.tushar.nanochat.handset.parseModelOutput
import ai.tushar.nanochat.handset.stepLabel
import kotlinx.coroutines.flow.takeWhile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** How one tool call ended: the outcome, what the model reads and what the person sees. */
class ToolRun(val outcome: StepOutcome, val forModel: String, val forPerson: String)

/** The phone as one reply sees it: the tools offered and how to call one. */
class PhoneAccess(
    val schemas: Map<String, JsonObject>,
    val call: suspend (ToolRequest) -> ToolRun,
    /** Read before every call. False once the companion's Use this phone switch is off, which ends the reply's tool use. */
    val allowed: suspend () -> Boolean = { true },
)

/** Room kept for the model's next call when sizing a result. */
private const val CallReserve = 60
private const val MinResultTokens = 32

internal suspend fun countOrEstimate(backend: ModelBackend, text: String): Int =
    runCatching { backend.countTokens(null, text) }.getOrElse { estimateTokens(text) }

/**
 * Generates one reply, running the phone tools the model calls in between, up to [MaxToolCalls]. [steps] collects
 * each tool step; [show] gets the reply text so far and whether a tool is running.
 */
internal class ReplyLoop(
    private val backend: ModelBackend,
    private val agentName: String,
    private val phone: PhoneAccess?,
    private val native: Boolean,
    private val budget: Int,
    var request: GenerateRequest,
    var used: Int,
    val steps: MutableList<ToolStep>,
    private val show: (text: String, running: Boolean) -> Unit,
    private val elapsedMs: () -> Long,
) {
    val out = StringBuilder()
    var firstTokenMs: Long? = null
    var finish: String? = null
    var stats: RunStats? = null
    /** Set when the reply is a fixed line instead of the model's words, as when it kept getting the call format wrong. */
    var fallback: String? = null

    /** True once a tool result is in the context. */
    private val readFromPhone: Boolean get() = steps.any { it.tool.isNotEmpty() }

    /** An acting call after a read: shown as a step, and the model is told why it didn't run. */
    private suspend fun refuse(said: String, call: ToolRequest) {
        steps += ToolStep(
            messageId = 0, position = steps.size, tool = call.tool, arguments = call.arguments.toString(), label = NotActedLabel,
            outcome = StepOutcome.Refused.name, result = NotActedForPerson, sentChars = NotActedForPerson.length,
        )
        val message = notActedMessage(call.tool)
        val modelSaid = if (native) said else callJson(call)
        request = request.withRound(modelSaid, call, message, call.tool, agentName)
        used += countOrEstimate(backend, modelSaid + message)
        show("", false)
    }

    /**
     * Puts the steps a stopped reply already took back into the request, as the calls and results they were, so Try
     * again goes on from them instead of doing them again. [saved] must already be in [steps].
     */
    suspend fun replay(saved: List<ToolStep>) {
        val taken = saved.filter { it.tool.isNotEmpty() }
        taken.forEachIndexed { i, s ->
            val arguments = runCatching { Json.parseToJsonElement(s.arguments).jsonObject }.getOrDefault(JsonObject(emptyMap()))
            val call = ToolRequest(s.tool, arguments)
            val message = when {
                s.label == NotActedLabel -> notActedMessage(s.tool)
                s.outcome == StepOutcome.Done.name -> resultMessage(s.tool, s.result.take(s.sentChars), native, last = i + 1 >= MaxToolCalls)
                else -> resultMessage(s.tool, "Error: ${s.result}", native, last = i + 1 >= MaxToolCalls)
            }
            val modelSaid = if (native) "" else callJson(call)
            request = request.withRound(modelSaid, call, message, s.tool, agentName)
            used += countOrEstimate(backend, modelSaid + message)
        }
        if (native && taken.isNotEmpty()) request = request.copy(tools = readOnlySpecs(request.tools))
    }

    suspend fun run() {
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
                    if (firstTokenMs == null) firstTokenMs = elapsedMs()
                    out.append(chunk.text)
                    val raw = out.toString()
                    hitNextTurn = startsNextTurn(raw, agentName)
                    if (hitNextTurn) finish = "STOP (cut at next turn)"
                    if (hideCalls && mightBeCall(raw)) {
                        callDone = callClosed(raw)
                        show("", false)
                    } else {
                        show(cleanReply(raw, agentName), false)
                    }
                }
                calls += chunk.toolCalls
                chunk.finishReason?.let { finish = it }
                chunk.stats?.let { stats = it }
            }
            if (phone == null) break
            val said = cleanReply(out.toString(), agentName)
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
                    request = request.withRound(said, calls.firstOrNull(), message, calls.firstOrNull()?.tool, agentName)
                    used += countOrEstimate(backend, said + message)
                }
                is ModelOutput.Call -> {
                    if (steps.count { it.tool.isNotEmpty() } >= MaxToolCalls) {
                        fallback = "I've used my $MaxToolCalls phone steps for this reply. Tap a step to see what came back."
                        break
                    }
                    if (!phone.allowed()) {
                        fallback = "Use this phone was turned off, so I stopped there."
                        break
                    }
                    val call = decision.request
                    // Once a result is in the context, text from the phone could be steering the model, so it can't act.
                    if (readFromPhone && PhoneTool.acts(call.tool, call.arguments)) {
                        refuse(said, calls.firstOrNull() ?: call)
                        continue
                    }
                    show("", true)
                    val ran = phone.call(call)
                    val callsLeft = MaxToolCalls - steps.size
                    val share = (budget - used - CallReserve) / minOf(2, callsLeft).coerceAtLeast(1)
                    val fitted = fitToBudget(ran.forModel, share.coerceAtLeast(MinResultTokens)) { countOrEstimate(backend, it) }
                    steps += ToolStep(
                        messageId = 0, position = steps.size, tool = call.tool, arguments = call.arguments.toString(),
                        label = stepLabel(call.tool, call.arguments, ran.outcome), outcome = ran.outcome.name,
                        result = ran.forPerson.take(ToolStep.MaxResultChars),
                        sentChars = if (ran.outcome == StepOutcome.Done && fitted.cut) fitted.keptChars else ran.forPerson.length,
                    )
                    // An opened app puts this one in the background, where Nano can't answer, so the step is the reply.
                    if (backend.foregroundOnly && call.tool == PhoneTool.OpenApp.slug && ran.outcome == StepOutcome.Done) {
                        fallback = "${steps.last().label}."
                        break
                    }
                    val last = steps.size >= MaxToolCalls
                    val message = resultMessage(call.tool, fitted.text, native, last)
                    // Native chat replays the model's own call, so its cache still matches; the JSON format gets the call cleaned up.
                    val modelSaid = if (native) said else callJson(call)
                    request = request.withRound(modelSaid, calls.firstOrNull() ?: call, message, call.tool, agentName)
                    if (native) request = request.copy(tools = readOnlySpecs(request.tools))
                    used += countOrEstimate(backend, modelSaid + message)
                    show("", false)
                }
            }
        }
    }
}
