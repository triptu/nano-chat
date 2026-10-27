package ai.tushar.nanochat.handset

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallsTest {
    private fun schema(text: String) = Json.parseToJsonElement(text).jsonObject

    private val tools = mapOf(
        "search_context" to schema("""{"type":"object","properties":{"query":{"type":"string"},"days":{"type":"integer"}},"required":["query"]}"""),
        "set_do_not_disturb" to schema("""{"type":"object","properties":{"on":{"type":"boolean"}},"required":["on"]}"""),
        "device_status" to schema("""{"type":"object","properties":{}}"""),
    )

    private fun call(raw: String) = parseModelOutput(raw, tools) as? ModelOutput.Call ?: error("not a call: ${parseModelOutput(raw, tools)}")

    @Test
    fun readsAPlainCall() {
        val c = call("""{"tool": "search_context", "arguments": {"query": "Toit", "days": 30}}""")
        assertEquals("search_context", c.request.tool)
        assertEquals(JsonPrimitive("Toit"), c.request.arguments["query"])
        assertEquals(JsonPrimitive(30), c.request.arguments["days"])
    }

    @Test
    fun readsACallInAFenceOrAfterWords() {
        assertEquals("device_status", call("```json\n{\"tool\": \"device_status\", \"arguments\": {}}\n```").request.tool)
        assertEquals("search_context", call("Let me check. {\"tool\": \"search_context\", \"arguments\": {\"query\": \"x\"}}").request.tool)
        // Missing arguments mean none, and "name" works for "tool".
        assertEquals("device_status", call("""{"name": "device_status"}""").request.tool)
    }

    @Test
    fun fixesLooseArgumentTypes() {
        val c = call("""{"tool": "search_context", "arguments": {"query": "rent", "days": "14"}}""")
        assertEquals(JsonPrimitive(14L), c.request.arguments["days"])
        val dnd = call("""{"tool": "set_do_not_disturb", "arguments": {"on": "true"}}""")
        assertEquals(JsonPrimitive(true), dnd.request.arguments["on"])
    }

    @Test
    fun plainWordsAreAnAnswer() {
        val out = parseModelOutput("  Sure, your next alarm is at 7:30.  ", tools)
        assertEquals(ModelOutput.Answer("Sure, your next alarm is at 7:30."), out)
        // Braces inside an answer aren't a call.
        assertTrue(parseModelOutput("Use {curly} braces like this.", tools) is ModelOutput.Answer)
    }

    @Test
    fun wrongCallsSayWhatToFix() {
        fun reason(raw: String) = (parseModelOutput(raw, tools) as ModelOutput.Invalid).reason
        assertTrue(reason("""{"tool": "search_context", "arguments": {"query": "x"""").contains("isn't closed"))
        assertTrue(reason("""{"tool": "tap", "arguments": {}}""").contains("no tool called tap"))
        assertTrue(reason("""{"tool": "search_context", "arguments": {}}""").contains("needs query"))
        assertTrue(reason("""{"tool": "search_context", "arguments": "Toit"}""").contains("must be an object"))
        assertTrue(reason("""{"arguments": {}}""").contains("no \"tool\""))
        assertTrue(reason("""{tool: search_context arguments}""").contains("valid JSON"))
    }

    /** The engine retries once with the reason; a corrected second try then parses as a call. */
    @Test
    fun aRetryAfterAWrongCallCanSucceed() {
        val first = parseModelOutput("""{"tool": "search_context", "arguments": {"q": "Toit"}}""", tools)
        assertTrue(first is ModelOutput.Invalid)
        val second = parseModelOutput("""{"tool": "search_context", "arguments": {"query": "Toit"}}""", tools)
        assertTrue(second is ModelOutput.Call)
    }

    @Test
    fun streamingHidesWhatLooksLikeACallAndStopsWhenItCloses() {
        assertTrue(mightBeCall("  {\"to"))
        assertTrue(mightBeCall("```"))
        assertFalse(mightBeCall("Sure"))
        assertFalse(mightBeCall(""))
        assertFalse(callClosed("""{"tool": "device_status", "arguments": {"""))
        assertTrue(callClosed("""{"tool": "device_status", "arguments": {}} and then"""))
        // A brace inside a string doesn't close the object.
        assertFalse(callClosed("""{"tool": "search_context", "arguments": {"query": "}"""))
    }

    @Test
    fun checksNativeCallsTheSameWay() {
        val ok = checkCall(ToolRequest("search_context", JsonObject(mapOf("query" to JsonPrimitive("Toit")))), tools)
        assertTrue(ok is ModelOutput.Call)
        assertTrue(checkCall(ToolRequest("get_screen", JsonObject(emptyMap())), tools) is ModelOutput.Invalid)
    }

    private val fourCharsPerToken: suspend (String) -> Int = { (it.length + 3) / 4 }

    @Test
    fun shortResultsAreLeftAlone() = runTest {
        val r = fitToBudget("Battery 80%, charging.", budget = 100, count = fourCharsPerToken)
        assertFalse(r.cut)
        assertEquals("Battery 80%, charging.", r.text)
    }

    @Test
    fun longResultsAreCutToTheBudgetAtALineAndSaySo() = runTest {
        val lines = (1..200).joinToString("\n") { "27 Oct 14:%02d · WhatsApp · notification from Sam: dinner at Toit at eight?".format(it % 60) }
        val r = fitToBudget(lines, budget = 300, count = fourCharsPerToken)
        assertTrue(r.cut)
        assertTrue("within budget: ${fourCharsPerToken(r.text)}", fourCharsPerToken(r.text) <= 300)
        assertTrue(r.text.endsWith("[Cut to fit: showing ${r.keptChars} of ${lines.length} characters.]"))
        val kept = r.text.substringBefore("\n[Cut to fit")
        assertTrue("ends at a whole line", lines.lines().contains(kept.lines().last()))
        assertTrue(r.keptChars > 600)
    }

    @Test
    fun aTinyBudgetStillSaysWhatWasCut() = runTest {
        val r = fitToBudget("x".repeat(5000), budget = 12, count = fourCharsPerToken)
        assertTrue(r.cut)
        assertTrue(r.text.contains("of 5000 characters"))
    }

    @Test
    fun stepLinesReadAsDoneOrNot() {
        fun args(vararg p: Pair<String, Any>) = JsonObject(p.associate { (k, v) -> k to if (v is Number) JsonPrimitive(v) else if (v is Boolean) JsonPrimitive(v) else JsonPrimitive(v.toString()) })
        assertEquals("Searched your phone for “Toit”", stepLabel("search_context", args("query" to "Toit"), StepOutcome.Done))
        assertEquals("Opened Clock", stepLabel("open_app", args("app" to "Clock"), StepOutcome.Done))
        assertEquals("Couldn't open Clock", stepLabel("open_app", args("app" to "Clock"), StepOutcome.Failed))
        assertEquals("Set an alarm for 07:05", stepLabel("set_alarm", args("hour" to 7, "minute" to 5), StepOutcome.Done))
        assertEquals("Started a timer for 5 min", stepLabel("set_timer", args("seconds" to 300), StepOutcome.Done))
        assertEquals("Turned Do not disturb off", stepLabel("set_do_not_disturb", args("on" to false), StepOutcome.Done))
    }

    @Test
    fun refusalsAreRewordedForThePerson() {
        val handset = "Notifications access is off for Nano Chat. Ask the user to turn on Notifications for Nano Chat in Handset's Settings, under Connected agents."
        assertTrue(isRefusal(handset))
        assertEquals(
            "Notifications access is off for Nano Chat. Turn on Notifications for Nano Chat in Handset's Settings, under Connected agents.",
            refusalForPerson(handset),
        )
        assertFalse(isRefusal("No app called Mpas"))
    }
}
