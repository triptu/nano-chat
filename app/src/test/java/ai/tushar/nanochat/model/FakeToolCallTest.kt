package ai.tushar.nanochat.model

import ai.tushar.nanochat.handset.ModelOutput
import ai.tushar.nanochat.handset.ToolRequest
import ai.tushar.nanochat.handset.parseModelOutput
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The fake model's scripted phone steps, driven through the same rounds the engine adds. */
class FakeToolCallTest {
    private val fake = FakeBackend(random = Random(1), pace = FakePace(firstTokenMs = 0..0, tokensPerSecond = 1000..1000))
    private val schemas = mapOf(
        "search_context" to Json.parseToJsonElement("""{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}""").jsonObject,
        "open_app" to Json.parseToJsonElement("""{"type":"object","properties":{"app":{"type":"string"}},"required":["app"]}""").jsonObject,
    )
    private val system = "You are Nano.\n\n" + toolInstructions(schemas.keys, native = false)

    private fun request(prompt: String) = GenerateRequest(system, prompt, 0.7f, 40, 512)

    private suspend fun say(r: GenerateRequest) = fake.generate(r).toList().joinToString("") { it.text }

    @Test
    fun searchesThenOpensThenSaysWhatItFound() = runTest {
        var r = request("## Conversation\nUser: Search my phone for Toit and open Maps\nNano:")
        val first = parseModelOutput(say(r), schemas) as ModelOutput.Call
        assertEquals(ToolRequest("search_context", first.request.arguments), first.request)
        assertEquals(JsonPrimitive("Toit"), first.request.arguments["query"])

        r = r.withRound(callJson(first.request), first.request, resultMessage("search_context", "<<content>>\n1 matches for “Toit”:\n27 Oct 19:02 · WhatsApp · notification from Sam: Toit at 8?\n<<end>>", false, false), "search_context", "Nano")
        val second = parseModelOutput(say(r), schemas) as ModelOutput.Call
        assertEquals("open_app", second.request.tool)
        assertEquals(JsonPrimitive("Maps"), second.request.arguments["app"])

        r = r.withRound(callJson(second.request), second.request, resultMessage("open_app", "Opened Maps", false, false), "open_app", "Nano")
        val answer = parseModelOutput(say(r), schemas)
        assertTrue(answer is ModelOutput.Answer)
        val text = (answer as ModelOutput.Answer).text
        assertTrue(text, text.contains("Found “Toit” on your phone: 27 Oct 19:02 · WhatsApp"))
        assertTrue(text, text.contains("I opened Maps"))
    }

    @Test
    fun withoutToolsItChatsAsBefore() = runTest {
        val plain = GenerateRequest("You are Nano.", "## Conversation\nUser: Search my phone for Toit\nNano:", 0.7f, 40, 512)
        assertTrue(parseModelOutput(say(plain), schemas) is ModelOutput.Answer)
    }

    @Test
    fun aRefusalComesBackInTheAnswer() = runTest {
        var r = request("## Conversation\nUser: Find Toit\nNano:")
        val call = parseModelOutput(say(r), schemas) as ModelOutput.Call
        r = r.withRound(callJson(call.request), call.request, resultMessage("search_context", "Error: The phone isn't recording Screen text or Notifications.", false, false), "search_context", "Nano")
        assertTrue(say(r).contains("isn't recording Screen text"))
    }
}
