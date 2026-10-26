package ai.tushar.nanochat.model

import ai.tushar.nanochat.data.Role

data class Turn(val role: Role, val text: String)

/** What gets sent to the model for one reply, and how much history made it in. */
data class PromptPlan(
    val systemPrompt: String,
    val prompt: String,
    val useSystemInstruction: Boolean,
    val includedTurns: Int,
    val droppedTurns: Int,
    val inputTokens: Int,
    val budget: Int,
)

sealed interface PromptResult {
    data class Fits(val plan: PromptPlan) : PromptResult
    data class TooLong(val tokens: Int, val budget: Int) : PromptResult
}

/** Counts input tokens for a system prompt (null when it goes inline) and prompt text. */
fun interface TokenCounter {
    suspend fun count(systemPrompt: String?, prompt: String): Int
}

/**
 * Builds the prompt from the agent's personality plus as many recent turns as fit in [budget] tokens.
 * [newestFirst] must start with the user's latest message, which is always kept.
 */
class PromptWindow(private val agentName: String, private val userName: String) {

    fun systemPrompt(personality: String): String = buildString {
        append(personality.trim())
        if (userName.isNotBlank()) append("\nYou are chatting with ${userName.trim()}.")
        append("\nReply as $agentName with just your next message: 2 to 5 friendly, specific sentences. Use a numbered list only for step-by-step instructions.")
        append(" Don't write the other person's lines.")
    }

    /** The instructions block that starts the prompt when the model has no system instructions. */
    fun instructions(systemPrompt: String): String = "## Instructions\n$systemPrompt\n\n"

    fun render(systemPrompt: String, chronological: List<Turn>, inlineSystem: Boolean): String = buildString {
        if (inlineSystem) append(instructions(systemPrompt))
        append("## Conversation\n")
        for (turn in chronological) {
            append(if (turn.role == Role.User) "User" else agentName).append(": ").append(turn.text.trim()).append('\n')
        }
        append(agentName).append(':')
    }

    suspend fun build(
        personality: String,
        newestFirst: List<Turn>,
        budget: Int,
        useSystemInstruction: Boolean,
        counter: TokenCounter,
    ): PromptResult {
        require(newestFirst.isNotEmpty() && newestFirst.first().role == Role.User)
        val system = systemPrompt(personality)
        suspend fun countFor(k: Int): Pair<String, Int> {
            val text = render(system, newestFirst.take(k).asReversed(), inlineSystem = !useSystemInstruction)
            return text to counter.count(if (useSystemInstruction) system else null, text)
        }
        fun plan(k: Int, text: String, tokens: Int) = PromptPlan(
            systemPrompt = system,
            prompt = text,
            useSystemInstruction = useSystemInstruction,
            includedTurns = k,
            droppedTurns = newestFirst.size - k,
            inputTokens = tokens,
            budget = budget,
        )

        var k = estimateFit(system, newestFirst, budget).coerceIn(1, newestFirst.size)
        var (text, tokens) = countFor(k)
        while (tokens > budget && k > 1) {
            k--
            countFor(k).let { text = it.first; tokens = it.second }
        }
        if (tokens > budget) return PromptResult.TooLong(tokens, budget)
        while (k < newestFirst.size) {
            val (nextText, nextTokens) = countFor(k + 1)
            if (nextTokens > budget) break
            k++; text = nextText; tokens = nextTokens
        }
        return PromptResult.Fits(plan(k, text, tokens))
    }

    private fun estimateFit(system: String, newestFirst: List<Turn>, budget: Int): Int {
        var used = estimate(system) + 16
        var k = 0
        for (turn in newestFirst) {
            used += estimate(turn.text) + 4
            if (used > budget) break
            k++
        }
        return k
    }

    private fun estimate(text: String): Int = (text.length + 2) / 3
}
