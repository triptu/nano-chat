package ai.tushar.nanochat.model

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Duration
import kotlin.random.Random

/** What the fake model pretends is going on. Picked on the nerds page. */
enum class FakeScenario(val label: String) {
    Normal("Works normally"),
    NeedsDownload("Needs a download"),
    Busy("Busy"),
    Quota("Out of quota"),
    Unsupported("Unsupported phone"),
}

/** Timing of fake replies. Defaults match what Gemini Nano feels like on a recent phone. */
data class FakePace(
    val firstTokenMs: IntRange = 300..800,
    val tokensPerSecond: IntRange = 20..40,
    val downloadStepMs: Long = 120,
)

/**
 * A stand-in model for emulators and tests. Streams canned replies that suit the agent's personality,
 * token by token. Seed [random] to make it deterministic.
 */
class FakeBackend(
    var scenario: FakeScenario = FakeScenario.Normal,
    private val random: Random = Random.Default,
    private val pace: FakePace = FakePace(),
) : ModelBackend {
    override val kind = BackendKind.Fake
    private var downloaded = false

    override suspend fun availability(): Availability = when (scenario) {
        FakeScenario.Unsupported -> Availability.Unavailable
        FakeScenario.NeedsDownload -> if (downloaded) Availability.Available else Availability.Downloadable
        else -> Availability.Available
    }

    override fun download(): Flow<DownloadEvent> = flow {
        val total = 1_480_000_000L
        emit(DownloadEvent.Started(total))
        var done = 0L
        while (done < total) {
            delay(pace.downloadStepMs)
            done = (done + total / 60).coerceAtMost(total)
            emit(DownloadEvent.Progress(done))
        }
        downloaded = true
        emit(DownloadEvent.Completed)
    }

    override suspend fun warmup() = delay(250)
    override suspend fun tokenLimit(): Int = 4096
    override suspend fun supportsSystemPrompt(): Boolean = true
    override suspend fun countTokens(systemPrompt: String?, prompt: String): Int = estimateTokens(systemPrompt.orEmpty()) + estimateTokens(prompt)

    override fun generate(request: GenerateRequest): Flow<GenerateChunk> = flow {
        when (scenario) {
            FakeScenario.Busy -> throw ModelException(ModelErrorKind.Busy, 9, "BUSY", "Fake: model busy")
            FakeScenario.Quota -> throw ModelException(
                ModelErrorKind.Quota, 27, "PER_APP_BATTERY_USE_QUOTA_EXCEEDED", "Fake: quota", Duration.ofMinutes(20),
            )
            FakeScenario.Unsupported -> throw ModelException(ModelErrorKind.NotReady, 8, "NOT_AVAILABLE", "Fake: unsupported")
            else -> Unit
        }
        val reply = pickReply(request)
        val tokens = splitTokens(reply)
        val limited = tokens.take(request.maxOutputTokens)
        delay(pace.firstTokenMs.random(random).toLong())
        val perToken = 1000L / pace.tokensPerSecond.random(random)
        limited.forEachIndexed { i, t ->
            if (i > 0) delay(perToken)
            emit(GenerateChunk(t, null))
        }
        emit(GenerateChunk("", if (limited.size < tokens.size) "MAX_TOKENS" else "STOP"))
    }

    override suspend fun info() = BackendInfo(baseModelName = "fake-nano", cachingAvailable = false, aicoreVersion = null)

    private fun pickReply(request: GenerateRequest): String {
        val persona = (request.systemPrompt ?: request.prompt).lowercase()
        val pool = when {
            listOf("cook", "recipe", "dinner").any { it in persona } -> CookReplies
            listOf("pep", "encourag", "upbeat").any { it in persona } -> PepReplies
            else -> HelperReplies
        }
        val lastUser = request.prompt.lines().lastOrNull { it.startsWith("User:") }.orEmpty()
        return pool[(lastUser.hashCode() xor random.nextInt()).mod(pool.size)]
    }
}

internal fun estimateTokens(text: String): Int = if (text.isEmpty()) 0 else (text.length + 3) / 4

/** Splits text into word-sized pieces that keep their spacing, so joining them restores the text. */
internal fun splitTokens(text: String): List<String> = Regex("\\s*\\S+").findAll(text).map { it.value }.toList()

private val HelperReplies = listOf(
    "Good question! The short version: it depends on what you want to optimise for.\n\n" +
        "If you care most about speed, pick the simpler option and revisit it later. If you care about getting it right the first time, spend ten minutes writing down what \"done\" looks like before you start.\n\n" +
        "Want me to help you sketch that list?",
    "Here's how I'd think about it:\n\n1. Start with the one thing that's blocking you.\n2. Do the smallest version of it today.\n3. Check how it feels tomorrow before adding more.\n\nMost plans get easier once the first step is real.",
    "Sure. In plain words: a cache keeps a copy of something you used recently, so the next time you ask for it, it comes back faster. The trade-off is that the copy can go stale, so caches usually forget things after a while.",
    "I'm not totally sure about that one, and I'd rather not guess. I run fully on your phone, so I can't look things up online. If you can tell me a bit more, I'll do my best with what I know.",
    "Happy to help! A quick summary:\n\n• Morning: deep work, phone in another room.\n• Afternoon: meetings and messages.\n• Evening: a short walk, then something fun.\n\nIt's simple, but simple is easy to keep.",
)

private val CookReplies = listOf(
    "Ooh, nice. With eggs, spinach and a bit of cheese you're one pan away from a frittata.\n\n" +
        "1. Whisk 6 eggs with salt, pepper and a splash of milk.\n2. Wilt the spinach in an oven-safe pan with olive oil.\n3. Pour in the eggs, scatter the cheese, cook 4 minutes on the stove.\n4. Finish under the grill for 3 to 4 minutes until puffed.\n\nServe with toast. Leftovers are great cold the next day.",
    "For a quick weeknight pasta: boil the water first, it's always the slow part.\n\nWhile it heats, fry two sliced garlic cloves in olive oil until just golden, add a pinch of chilli flakes and a tin of cherry tomatoes. Simmer ten minutes, toss with the pasta and a ladle of pasta water. Basil on top if you have it.",
    "No buttermilk? Easy swap: stir a tablespoon of lemon juice or vinegar into a cup of milk and let it sit for five minutes. It'll thicken slightly and work just the same in pancakes or a marinade.",
    "Rice tip that changed my life: rinse it until the water runs mostly clear, then use 1 part rice to 1.25 parts water. Bring to a boil, lid on, lowest heat for 12 minutes, then leave it alone for 10 more. Fluff with a fork. No peeking!",
)

private val PepReplies = listOf(
    "Hey, you showed up, and that counts for a lot. Feeling stuck doesn't mean you're behind. It usually means you care about doing it well.\n\nSmall next step: set a timer for 15 minutes and work on just the first piece. You can stop when it rings.",
    "That sounds like a lot to carry this week. You don't have to fix all of it today.\n\nPick the one thing that would make tomorrow lighter and do only that. Then do something nice for yourself, even if it's just a proper lunch away from the screen.",
    "Honestly? You've handled harder things than this. Remember how unsure you felt before your last big launch, and it went fine.\n\nWrite down the three things you're most worried about. Seeing them on paper usually makes them smaller.",
    "Love that you're trying something new! The first few tries are supposed to feel clumsy. That's what learning feels like from the inside.\n\nNext step: do one more practice round today and note one thing that went better than last time.",
)
