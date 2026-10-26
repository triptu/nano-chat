package ai.tushar.nanochat.model

import ai.tushar.nanochat.data.Role
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

class PromptAndFakeModelTest {
    private val fake = FakeBackend(random = Random(7))
    private val window = PromptWindow(agentName = "Basil", userName = "Sam")

    private fun longChat(turns: Int): List<Turn> = (turns downTo 1).map { i ->
        Turn(if (i % 2 == 1) Role.User else Role.Agent, "Message number $i. " + "Let's talk about dinner plans and leftovers. ".repeat(6))
    }

    @Test
    fun longChatKeepsNewestTurnsWithinBudget() = runTest {
        val history = longChat(81)
        val result = window.build("You are Basil, a home cook.", history, budget = 1200, useSystemInstruction = true, counter = fake::countTokens)
        val plan = (result as PromptResult.Fits).plan

        assertTrue("used ${plan.inputTokens} of ${plan.budget}", plan.inputTokens <= 1200)
        assertTrue("should drop old turns", plan.droppedTurns > 0)
        assertEquals(history.size, plan.includedTurns + plan.droppedTurns)
        assertTrue(plan.prompt.endsWith("Basil:"))
        assertTrue("newest message is last", plan.prompt.substringBeforeLast("\nBasil:").trimEnd().endsWith(history.first().text.trim()))
        assertTrue("oldest included turn is the right one", plan.prompt.contains("Message number ${history.size + 1 - plan.includedTurns}."))
        assertTrue(!plan.prompt.contains("Message number ${history.size - plan.includedTurns}."))
        assertTrue(plan.systemPrompt.contains("chatting with Sam"))

        val roomier = window.build("You are Basil, a home cook.", history, budget = 1200 + 200, useSystemInstruction = true, counter = fake::countTokens)
        assertTrue((roomier as PromptResult.Fits).plan.includedTurns > plan.includedTurns)
    }

    @Test
    fun withoutSystemInstructionsThePersonalityGoesInline() = runTest {
        val plan = (window.build("You are Basil.", longChat(3), 4000, useSystemInstruction = false, counter = fake::countTokens) as PromptResult.Fits).plan
        assertTrue(plan.prompt.startsWith("## Instructions\nYou are Basil."))
        assertEquals(0, plan.droppedTurns)
    }

    @Test
    fun oneHugeMessageIsTooLong() = runTest {
        val result = window.build("You are Basil.", listOf(Turn(Role.User, "x".repeat(20_000))), 1000, true, fake::countTokens)
        assertTrue(result is PromptResult.TooLong)
    }

    @Test
    fun fakeStreamsAReplyThatSuitsTheAgentAndTheQuestion() = runTest {
        val request = GenerateRequest(
            systemPrompt = "You are Basil, a relaxed home cook.",
            prompt = "## Conversation\nUser: I have eggs, spinach and cheese. Dinner ideas?\nBasil:",
            temperature = 0.7f, topK = 40, maxOutputTokens = 512,
        )
        val chunks = fake.generate(request).toList()
        val text = chunks.joinToString("") { it.text }
        assertTrue(chunks.size > 20)
        assertTrue(text, text.contains("frittata"))
        assertEquals("STOP", chunks.last().finishReason)

        val capped = fake.generate(request.copy(maxOutputTokens = 10)).toList()
        assertEquals(11, capped.size)
        assertEquals("MAX_TOKENS", capped.last().finishReason)
    }

    @Test
    fun fakeQuotaScenarioFailsLikeTheRealApi() = runTest {
        fake.scenario = FakeScenario.Quota
        try {
            fake.generate(GenerateRequest(null, "User: hi\nNano:", 0.7f, 40, 100)).toList()
            fail("expected a quota error")
        } catch (e: ModelException) {
            assertEquals(ModelErrorKind.Quota, e.kind)
            assertTrue(e.retryAfter.toMinutes() > 0)
        }
    }

    @Test
    fun replyStopsAtAMadeUpNextTurn() {
        assertEquals("Sure, try a frittata.", cleanReply("Basil: Sure, try a frittata.\nUser: thanks!", "Basil"))
    }
}
