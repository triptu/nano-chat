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
    LoadFails("Model fails to load"),
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
    /** Which model this stands in for; sets its media support, size and context. */
    private val model: ModelId = ModelId.Nano,
) : ModelBackend {
    override val kind = BackendKind.Fake
    override val media: MediaSupport = when (model) {
        ModelId.Nano -> MediaSupport(maxImages = 1, imagesInNewestTurnOnly = true, tokensPerImage = 260, audio = false)
        ModelId.GemmaE4B -> MediaSupport(maxImages = 4, imagesInNewestTurnOnly = false, tokensPerImage = 280, audio = true, audioTokensPerSecond = 25)
    }
    override val nativeChat: Boolean = model != ModelId.Nano
    override val foregroundOnly: Boolean = model == ModelId.Nano
    override val downloadBytes: Long = if (model == ModelId.Nano) 1_480_000_000L else 3_659_530_240L
    private var downloaded = false

    override suspend fun availability(): Availability = when (scenario) {
        FakeScenario.Unsupported -> Availability.Unavailable
        FakeScenario.NeedsDownload -> if (downloaded) Availability.Available else Availability.Downloadable
        else -> Availability.Available
    }

    override fun download(): Flow<DownloadEvent> = flow {
        val total = downloadBytes
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

    override suspend fun delete() {
        downloaded = false
    }

    override suspend fun warmup() {
        delay(250)
        if (scenario == FakeScenario.LoadFails) throw ModelException(ModelErrorKind.NotReady, null, "LOAD_FAILED", "Fake: engine failed to load")
    }
    override suspend fun tokenLimit(): Int = if (model == ModelId.Nano) 4096 else 8192
    override suspend fun inputTokenCap(): Int = if (model == ModelId.Nano) 4000 else 8192
    override suspend fun supportsSystemPrompt(): Boolean = true
    override suspend fun supportsPrefixCaching(): Boolean = false
    override suspend fun countTokens(systemPrompt: String?, prompt: String): Int = estimateTokens(systemPrompt.orEmpty()) + estimateTokens(prompt)

    override fun generate(request: GenerateRequest): Flow<GenerateChunk> = flow {
        when (scenario) {
            FakeScenario.Busy -> throw ModelException(ModelErrorKind.Busy, 9, "BUSY", "Fake: model busy")
            FakeScenario.Quota -> throw ModelException(
                ModelErrorKind.Quota, 27, "PER_APP_BATTERY_USE_QUOTA_EXCEEDED", "Fake: quota", Duration.ofMinutes(20),
            )
            FakeScenario.Unsupported -> throw ModelException(ModelErrorKind.NotReady, 8, "NOT_AVAILABLE", "Fake: unsupported")
            FakeScenario.LoadFails -> throw ModelException(ModelErrorKind.NotReady, null, "LOAD_FAILED", "Fake: engine failed to load")
            else -> Unit
        }
        val reply = toolReply(request) ?: mediaReply(request) ?: pickReply(request)
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

    override suspend fun info() = BackendInfo(
        baseModelName = if (model == ModelId.Nano) "fake-nano" else "fake-gemma",
        cachingAvailable = false,
        accelerator = if (model == ModelId.Nano) null else "GPU",
        loadMs = if (model == ModelId.Nano) null else 250,
    )

    /**
     * A scripted phone step, so the tool loop can be tried without a real model. When the prompt offers tools, a message
     * like "Search my phone for Toit and open Maps" gets one call per thing asked, in order, then a line built from
     * what came back. Like a gullible model, it also does what a result tells it to, when that's "turn on do not
     * disturb", so the guard against acting on phone text can be seen working.
     */
    private fun toolReply(request: GenerateRequest): String? {
        val system = request.systemPrompt ?: (request.promptPrefix.orEmpty() + request.prompt)
        if ("You can use this phone through tools" !in system) return null
        val sinceUser = request.prompt.substringAfterLast("\nUser: ", missingDelimiterValue = "")
        val asked = sinceUser.lineSequence().firstOrNull().orEmpty()
        val rounds = sinceUser.split("\n$PhoneSpeaker: ").drop(1)
        val made = CallInPrompt.findAll(sinceUser).map { it.value }.toList()
        val told = rounds.lastOrNull()?.let(::dataLines).orEmpty().any { "turn on do not disturb" in it.lowercase() }
        if (told && made.none { "set_do_not_disturb" in it }) return """{"tool":"set_do_not_disturb","arguments":{"on":true}}"""
        scriptedCalls(asked).firstOrNull { it !in made }?.let { return it }
        if (rounds.isEmpty()) return null
        return made.zip(rounds).joinToString(" ") { (call, round) -> describeRound(call, round) }
    }

    /** What the user's message asks the phone for, as calls in the order asked. */
    private fun scriptedCalls(message: String): List<String> {
        val found = mutableListOf<Pair<Int, String>>()
        Regex("(?i)\\b(?:search|find|look for|look up)(?: my phone)?(?: for)? (.+?)(?= and |[.?!]|$)").find(message)?.let {
            found += it.range.first to call("search_context", "query", it.groupValues[1].trim().trim('"', '“', '”'))
        }
        Regex("(?i)\\bopen (?:the )?(.+?)(?: app)?(?= and |[.?!]|$)").find(message)?.let {
            found += it.range.first to call("open_app", "app", it.groupValues[1].trim())
        }
        Regex("(?i)\\bset an alarm for (\\d{1,2})(?::(\\d{2}))?").find(message)?.let {
            val minute = it.groupValues[2].ifEmpty { "0" }.toInt()
            found += it.range.first to """{"tool":"set_alarm","arguments":{"hour":${it.groupValues[1].toInt()},"minute":$minute}}"""
        }
        Regex("(?i)\\ba timer for (\\d+) ?(min|sec)").find(message)?.let {
            val seconds = it.groupValues[1].toInt() * if (it.groupValues[2].lowercase() == "min") 60 else 1
            found += it.range.first to """{"tool":"set_timer","arguments":{"seconds":$seconds}}"""
        }
        if (Regex("(?i)\\b(battery|phone status)\\b").containsMatchIn(message)) found += message.length to """{"tool":"device_status","arguments":{}}"""
        return found.sortedBy { it.first }.map { it.second }
    }

    private fun call(tool: String, key: String, value: String) =
        """{"tool":"$tool","arguments":{"$key":"${value.replace("\\", "").replace("\"", "")}"}}"""

    /** One sentence about one tool round, from the result text in the prompt. */
    private fun describeRound(call: String, round: String): String {
        val tool = Regex("\"tool\":\"(\\w+)\"").find(call)?.groupValues?.get(1)
        val value = Regex("\"arguments\":\\{\"\\w+\":\"([^\"]*)\"").find(call)?.groupValues?.get(1).orEmpty()
        if ("wasn't run" in round) return "I didn't do what the text on your phone asked. Ask me yourself if you want it done."
        val lines = dataLines(round).filter { it.isNotBlank() }
        lines.firstOrNull { it.startsWith("Error:") }?.let { return "I couldn't do that on your phone. ${it.removePrefix("Error:").trim()}" }
        return when (tool) {
            "search_context" -> lines.firstOrNull { " · " in it }?.let { "Found “$value” on your phone: ${it.take(140)}." }
                ?: "I couldn't find “$value” on your phone."
            "open_app" -> "I opened $value for you."
            "set_alarm" -> "I set the alarm."
            "set_timer" -> "I started the timer."
            "device_status" -> "Here's your phone: ${lines.take(2).joinToString("; ").take(140)}."
            else -> "Done."
        }
    }

    /** The lines of a result, without the marks that say they're data. */
    private fun dataLines(round: String) = round.lines().filter { it.startsWith("| ") || it == "|" }.map { it.removePrefix("|").removePrefix(" ") }

    /** A canned line that mentions the photos or voice notes on the newest message, if it has any. */
    private fun mediaReply(request: GenerateRequest): String? {
        val newest = request.turns.lastOrNull() ?: return null
        val photos = newest.media.count { it.kind == ai.tushar.nanochat.data.AttachmentKind.Image }
        val notes = newest.media.count { it.kind == ai.tushar.nanochat.data.AttachmentKind.Audio }
        if (photos == 0 && notes == 0) return null
        val what = listOfNotNull(
            when (photos) { 0 -> null; 1 -> "your photo"; else -> "your $photos photos" },
            when (notes) { 0 -> null; 1 -> "your voice note"; else -> "your $notes voice notes" },
        ).joinToString(" and ")
        return "Thanks for $what! I'm the fake model, so I can't really see or hear it, but on a real phone I'd describe it here and answer your question."
    }

    private fun pickReply(request: GenerateRequest): String {
        val persona = (request.systemPrompt ?: (request.promptPrefix.orEmpty() + request.prompt)).lowercase()
        val pool = when {
            listOf("cook", "recipe", "dinner").any { it in persona } -> CookReplies
            listOf("pep", "encourag", "upbeat").any { it in persona } -> PepReplies
            else -> HelperReplies
        }
        val lastUser = request.prompt.lines().lastOrNull { it.startsWith("User:") }.orEmpty().lowercase()
        val best = pool.maxBy { r -> r.keywords.count { it in lastUser } }
        return if (best.keywords.any { it in lastUser }) best.text else pool[random.nextInt(pool.size)].text
    }
}

/** A call the engine wrote back into the prompt, as [callJson] writes it. */
private val CallInPrompt = Regex("\\{\"tool\":\"\\w+\",\"arguments\":\\{[^}]*\\}\\}")

internal fun estimateTokens(text: String): Int = if (text.isEmpty()) 0 else (text.length + 3) / 4

/** Splits text into word-sized pieces that keep their spacing, so joining them restores the text. */
internal fun splitTokens(text: String): List<String> = Regex("\\s*\\S+").findAll(text).map { it.value }.toList()

private class Canned(val keywords: List<String>, val text: String)

private fun c(vararg keywords: String, text: () -> String) = Canned(keywords.toList(), text())

private val HelperReplies = listOf(
    c("decide", "choose", "which", "option", "should i") {
        "Good question! The short version: it depends on what you want to optimise for.\n\n" +
            "If you care most about speed, pick the simpler option and revisit it later. If you care about getting it right the first time, spend ten minutes writing down what \"done\" looks like before you start.\n\n" +
            "Want me to help you sketch that list?"
    },
    c("plan", "start", "stuck", "project", "goal") {
        "Here's how I'd think about it:\n\n1. Start with the one thing that's blocking you.\n2. Do the smallest version of it today.\n3. Check how it feels tomorrow before adding more.\n\nMost plans get easier once the first step is real."
    },
    c("what is", "explain", "cache", "how does", "mean") {
        "Sure. In plain words: a cache keeps a copy of something you used recently, so the next time you ask for it, it comes back faster. The trade-off is that the copy can go stale, so caches usually forget things after a while."
    },
    c("news", "today", "weather", "latest", "online") {
        "I'm not totally sure about that one, and I'd rather not guess. I run fully on your phone, so I can't look things up online. If you can tell me a bit more, I'll do my best with what I know."
    },
    c("morning", "routine", "day", "schedule", "calm") {
        "A calmer morning mostly comes down to fewer decisions before 9. Try this for a week:\n\n" +
            "1. Lay out clothes and fill the kettle the night before.\n2. Keep your phone on the other side of the room until after breakfast.\n3. Ten quiet minutes with coffee, a window, or a short walk.\n4. Write the one thing that matters most today on a sticky note.\n\n" +
            "Start with just one of these. Which feels easiest?"
    },
)

private val CookReplies = listOf(
    c("egg", "spinach", "cheese", "breakfast", "fridge") {
        "Ooh, nice. With eggs, spinach and a bit of cheese you're one pan away from a frittata.\n\n" +
            "1. Whisk 6 eggs with salt, pepper and a splash of milk.\n2. Wilt the spinach in an oven-safe pan with olive oil.\n3. Pour in the eggs, scatter the cheese, cook 4 minutes on the stove.\n4. Finish under the grill for 3 to 4 minutes until puffed.\n\nServe with toast. Leftovers are great cold the next day."
    },
    c("pasta", "tomato", "garlic", "quick", "weeknight", "dinner") {
        "For a quick weeknight pasta: boil the water first, it's always the slow part.\n\nWhile it heats, fry two sliced garlic cloves in olive oil until just golden, add a pinch of chilli flakes and a tin of cherry tomatoes. Simmer ten minutes, toss with the pasta and a ladle of pasta water. Basil on top if you have it."
    },
    c("buttermilk", "swap", "instead", "substitute", "milk") {
        "No buttermilk? Easy swap: stir a tablespoon of lemon juice or vinegar into a cup of milk and let it sit for five minutes. It'll thicken slightly and work just the same in pancakes or a marinade."
    },
    c("rice", "fluffy", "sticky", "grain") {
        "Rice tip that changed my life: rinse it until the water runs mostly clear, then use 1 part rice to 1.25 parts water. Bring to a boil, lid on, lowest heat for 12 minutes, then leave it alone for 10 more. Fluff with a fork. No peeking!"
    },
)

private val PepReplies = listOf(
    c("stuck", "procrastinat", "can't start", "motivat") {
        "Hey, you showed up, and that counts for a lot. Feeling stuck doesn't mean you're behind. It usually means you care about doing it well.\n\nSmall next step: set a timer for 15 minutes and work on just the first piece. You can stop when it rings."
    },
    c("tired", "overwhelm", "a lot", "stress", "busy", "week") {
        "That sounds like a lot to carry this week. You don't have to fix all of it today.\n\nPick the one thing that would make tomorrow lighter and do only that. Then do something nice for yourself, even if it's just a proper lunch away from the screen."
    },
    c("nervous", "worried", "scared", "interview", "presentation", "launch") {
        "Honestly? You've handled harder things than this. Remember how unsure you felt before your last big launch, and it went fine.\n\nWrite down the three things you're most worried about. Seeing them on paper usually makes them smaller."
    },
    c("new", "learning", "first time", "try", "practice") {
        "Love that you're trying something new! The first few tries are supposed to feel clumsy. That's what learning feels like from the inside.\n\nNext step: do one more practice round today and note one thing that went better than last time."
    },
)
