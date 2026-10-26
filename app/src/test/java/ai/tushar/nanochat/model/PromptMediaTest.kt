package ai.tushar.nanochat.model

import ai.tushar.nanochat.data.AttachmentKind
import ai.tushar.nanochat.data.Role
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptMediaTest {
    private val window = PromptWindow(agentName = "Basil", userName = "Sam")
    private val gemma = FakeBackend(model = ModelId.GemmaE4B)
    private val nano = FakeBackend(model = ModelId.Nano)

    private fun photo(n: Int) = TurnMedia(AttachmentKind.Image, "/photos/$n.png")
    private val voice = TurnMedia(AttachmentKind.Audio, "/notes/1.wav", durationMs = 8_000)

    /** Six photo messages, newest first, each answered by Basil. */
    private fun photoChat(): List<Turn> = (6 downTo 1).flatMap { i ->
        listOf(Turn(Role.User, "What about this one? ($i)", listOf(photo(i))), Turn(Role.Agent, "Looks tasty. ($i)"))
    }.drop(1).let { listOf(Turn(Role.User, "And this? (7)", listOf(photo(7)))) + it }

    @Test
    fun olderPhotosAreLeftOutOnceTheyPassTheMediaShare() = runTest {
        val budget = 2000 // 40% is 800 tokens, so two 280-token photos fit; the newest always goes in.
        val plan = (window.build("You are Basil.", photoChat(), budget, true, gemma::countTokens, gemma.media) as PromptResult.Fits).plan

        assertEquals(2, plan.mediaKept.images)
        assertTrue(plan.mediaDropped.images >= 4)
        assertTrue(plan.inputTokens <= budget)
        val newest = plan.turns.last()
        assertEquals(listOf(photo(7)), newest.media)
        val oldestWithText = plan.turns.first { it.role == Role.User }
        assertTrue("a dropped photo leaves a marker: ${oldestWithText.text}", oldestWithText.media.isEmpty() && oldestWithText.text.startsWith("[photo]"))
    }

    @Test
    fun nanoSeesOnlyTheNewestPhotoAndNoVoiceNotes() = runTest {
        val history = listOf(
            Turn(Role.User, "", listOf(photo(2), voice)),
            Turn(Role.Agent, "Nice."),
            Turn(Role.User, "Earlier", listOf(photo(1))),
        )
        val plan = (window.build("You are Basil.", history, 4000, false, nano::countTokens, nano.media) as PromptResult.Fits).plan

        assertEquals(MediaCount(images = 1, audio = 0), plan.mediaKept)
        assertEquals(MediaCount(images = 1, audio = 1), plan.mediaDropped)
        assertEquals(listOf(photo(2)), plan.turns.last().media)
        assertTrue(plan.prompt, plan.prompt.contains("User: [photo] [voice note, 8 s]"))
    }

    @Test
    fun fakeRepliesToAttachments() = runTest {
        val turns = listOf(Turn(Role.User, "", listOf(photo(1), voice)))
        val text = gemma.generate(GenerateRequest(null, "User: [photo]\nBasil:", 0.7f, 40, 200, turns = turns)).toList().joinToString("") { it.text }
        assertTrue(text, text.contains("your photo and your voice note"))
    }
}
