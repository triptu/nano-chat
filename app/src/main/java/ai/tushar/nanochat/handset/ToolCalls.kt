package ai.tushar.nanochat.handset

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** A tool call the model asked for. */
data class ToolRequest(val tool: String, val arguments: JsonObject)

/** What the model's output turned out to be, in the JSON call format. */
sealed interface ModelOutput {
    data class Call(val request: ToolRequest) : ModelOutput
    data class Answer(val text: String) : ModelOutput
    /** It tried to call a tool and got it wrong; [reason] goes back to the model for its one retry. */
    data class Invalid(val reason: String) : ModelOutput
}

private val lenient = Json { isLenient = true }

/**
 * Reads the model's reply in the call format the prompt asks for: one JSON object `{"tool": ..., "arguments": {...}}`,
 * alone or after a few words, optionally in a code fence. Anything else is an answer.
 */
fun parseModelOutput(raw: String, tools: Map<String, JsonObject>): ModelOutput {
    val text = raw.trim()
    val start = callStart(text) ?: return ModelOutput.Answer(text)
    val objectText = balancedObject(text, start) ?: return ModelOutput.Invalid("the JSON object isn't closed")
    val o = runCatching { lenient.parseToJsonElement(objectText) as? JsonObject }.getOrNull()
        ?: return ModelOutput.Invalid("that isn't valid JSON")
    val name = (o["tool"] ?: o["name"])?.let { (it as? JsonPrimitive)?.contentOrNull }
        ?: return ModelOutput.Invalid("it has no \"tool\" name")
    val given = when (val a = o["arguments"] ?: o["args"] ?: o["parameters"]) {
        null, JsonNull -> JsonObject(emptyMap())
        is JsonObject -> a
        else -> return ModelOutput.Invalid("\"arguments\" must be an object")
    }
    return checkCall(ToolRequest(name, given), tools)
}

/** Checks a call against the offered tools and their schemas, fixing argument types a small model got loose. */
fun checkCall(call: ToolRequest, tools: Map<String, JsonObject>): ModelOutput {
    val schema = tools[call.tool] ?: return ModelOutput.Invalid("there is no tool called ${call.tool}. The tools are ${tools.keys.joinToString()}")
    val arguments = coerce(call.arguments, schema)
    val missing = required(schema).filter { it !in arguments }
    if (missing.isNotEmpty()) return ModelOutput.Invalid("${call.tool} needs ${missing.joinToString(" and ")}")
    return ModelOutput.Call(ToolRequest(call.tool, arguments))
}

/** True once a streamed reply holds a whole call object, so generating can stop there. */
fun callClosed(partial: String): Boolean {
    val text = partial.trim()
    val start = callStart(text) ?: return false
    return balancedObject(text, start) != null
}

/** True once the start of a streamed reply could be a tool call, so the chat shouldn't show it as text. */
fun mightBeCall(partial: String): Boolean {
    val t = partial.trimStart()
    if (t.isEmpty()) return false
    return t.startsWith("{") || t.startsWith("`") || "{\"tool\"" in t.replace(" ", "")
}

/** Where the call's JSON object starts: at the very start, after a code fence, or at `{"tool"` after some words. */
private fun callStart(text: String): Int? {
    val unfenced = text.removePrefix("```json").removePrefix("```").trimStart()
    if (unfenced.startsWith("{")) return text.indexOf('{')
    return Regex("\\{\\s*\"(tool|name)\"\\s*:").find(text)?.range?.first
}

/** The object starting at [start], counting braces outside strings, or null if it never closes. */
private fun balancedObject(text: String, start: Int): String? {
    var depth = 0
    var inString = false
    var escaped = false
    for (i in start until text.length) {
        val c = text[i]
        when {
            escaped -> escaped = false
            c == '\\' && inString -> escaped = true
            c == '"' -> inString = !inString
            inString -> Unit
            c == '{' -> depth++
            c == '}' -> if (--depth == 0) return text.substring(start, i + 1)
        }
    }
    return null
}

private fun required(schema: JsonObject): List<String> =
    (schema["required"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

/** Small models write `"7"` for 7 and `"true"` for true; fixes those where the schema says the type. */
private fun coerce(arguments: JsonObject, schema: JsonObject): JsonObject {
    val props = schema["properties"] as? JsonObject ?: return arguments
    return JsonObject(
        arguments.mapValues { (key, value) ->
            val type = ((props[key] as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull
            val p = value as? JsonPrimitive
            when {
                p == null || !p.isString -> value
                type == "integer" -> p.content.trim().toLongOrNull()?.let(::JsonPrimitive) ?: value
                type == "number" -> p.content.trim().toDoubleOrNull()?.let(::JsonPrimitive) ?: value
                type == "boolean" -> p.content.trim().lowercase().toBooleanStrictOrNull()?.let(::JsonPrimitive) ?: value
                else -> value
            }
        },
    )
}

/** A tool result cut to fit the model's context. */
data class FittedResult(val text: String, val cut: Boolean, val keptChars: Int, val totalChars: Int)

/**
 * Cuts [text] so it costs at most [budget] tokens, by [count], ending at a line break where it can and saying what was
 * cut, so the model can tell the user. Counting is the backend's own where it has one.
 */
suspend fun fitToBudget(text: String, budget: Int, count: suspend (String) -> Int): FittedResult {
    val total = text.length
    val tokens = count(text)
    if (tokens <= budget) return FittedResult(text, cut = false, keptChars = total, totalChars = total)
    var keep = (total.toLong() * budget / tokens.coerceAtLeast(1)).toInt()
    repeat(8) {
        val candidate = cutAt(text, keep)
        val withNote = candidate + cutNote(candidate.length, total)
        if (count(withNote) <= budget || keep == 0) return FittedResult(withNote, cut = true, keptChars = candidate.length, totalChars = total)
        keep = (keep * 0.8).toInt()
    }
    val note = cutNote(0, total).trimStart()
    return FittedResult(note, cut = true, keptChars = 0, totalChars = total)
}

private fun cutNote(kept: Int, total: Int) = "\n[Cut to fit: showing $kept of $total characters.]"

/** The first [chars] of [text], ending at the last line break in its final quarter when there is one. */
private fun cutAt(text: String, chars: Int): String {
    if (chars <= 0) return ""
    val head = text.take(chars)
    val lineEnd = head.lastIndexOf('\n')
    return (if (lineEnd >= chars * 3 / 4) head.substring(0, lineEnd) else head).trimEnd()
}

internal fun JsonElement.intValue(): Int? = (this as? JsonPrimitive)?.let { it.intOrNull ?: it.longOrNull?.toInt() ?: it.contentOrNull?.toIntOrNull() }
internal fun JsonElement.boolValue(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
