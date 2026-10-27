package ai.tushar.nanochat.model

import ai.tushar.nanochat.data.Role
import ai.tushar.nanochat.handset.PhoneTool
import ai.tushar.nanochat.handset.ToolRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Most tools one reply may call. Each result takes context a small model doesn't have much of. */
const val MaxToolCalls = 3

/** Speaker label for tool results in a transcript prompt. */
const val PhoneSpeaker = "Phone"

/**
 * What the model is told about the phone's tools. Models without native tool calling get the JSON call format and a
 * one-line description of each tool; native ones get the tools declared and only a reminder.
 */
fun toolInstructions(tools: Collection<String>, native: Boolean, now: LocalDateTime = LocalDateTime.now()): String = buildString {
    val time = now.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH))
    if (native) {
        append("You can use this phone through your tools when a message needs it. Never make up what a tool returns. It is now $time.")
        return@buildString
    }
    append("You can use this phone through tools. To use one, reply with only one JSON object and nothing else, like:\n")
    append("{\"tool\": \"open_app\", \"arguments\": {\"app\": \"Clock\"}}\n")
    append("The result comes back in the next message as \"$PhoneSpeaker:\". Use a tool only when the message needs the phone, ")
    append("and never make up a result. At most $MaxToolCalls tools per reply.\nTools:\n")
    PhoneTool.entries.filter { it.slug in tools }.forEach { append("- ").append(it.brief).append('\n') }
    append("It is now $time.")
}

/** Tool declarations for a model with native tool calling: the short description, and the parameters without theirs. */
fun toolSpecs(schemas: Map<String, JsonObject>): List<ToolSpec> = PhoneTool.entries.mapNotNull { tool ->
    val schema = schemas[tool.slug] ?: return@mapNotNull null
    ToolSpec(tool.slug, tool.brief.substringAfter("): "), slim(schema))
}

/** The schema with property descriptions dropped; the tool's own line says enough, and every token counts. */
private fun slim(schema: JsonObject): JsonObject {
    val props = schema["properties"] as? JsonObject ?: return schema
    val slimmed = JsonObject(props.mapValues { (_, p) -> (p as? JsonObject)?.let { JsonObject(it - "description") } ?: p })
    return JsonObject(schema + ("properties" to slimmed))
}

/** The call as the model should have written it, which goes back into the prompt instead of whatever it wrapped it in. */
fun callJson(r: ToolRequest): String = buildJsonObject { put("tool", r.tool); put("arguments", r.arguments) }.toString()

/** What the model reads after a tool ran. Native backends get the bare result; the JSON format gets a nudge too. */
fun resultMessage(tool: String, result: String, native: Boolean, last: Boolean): String = when {
    native -> result
    last -> "Result of $tool:\n$result\nThat was the last tool for this reply. Now answer in plain words."
    else -> "Result of $tool:\n$result\nNow answer in plain words, or call one more tool."
}

fun retryMessage(reason: String): String =
    "That tool call didn't work: $reason. Reply with only {\"tool\": \"name\", \"arguments\": {...}}, or answer in plain words."

/**
 * The request with one more round on the end: what the model said (a call, or a call it got wrong) and the answer to it.
 * A transcript prompt gets both as lines; native chat gets them as turns.
 */
fun GenerateRequest.withRound(modelSaid: String, call: ToolRequest?, reply: String, replyTool: String?, agentName: String): GenerateRequest = copy(
    prompt = prompt + " " + modelSaid.trim() + "\n$PhoneSpeaker: " + reply.trim() + "\n$agentName:",
    turns = turns + Turn(Role.Agent, modelSaid.trim(), tool = call?.let(ToolTurn::Call)) +
        Turn(Role.User, reply, tool = replyTool?.let(ToolTurn::Result)),
)
