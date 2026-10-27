package ai.tushar.nanochat.handset

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The Handset tools a companion is offered: the ones whose answers are short and that a small model calls well.
 * Screen-driving tools (get_screen, tap, type_text, run_steps) are left out. A screen is thousands of tokens, more than
 * Gemma's 2,048-token window, and driving one takes many careful steps that small models get wrong.
 *
 * [brief] is the line the model reads, shorter than Handset's own description to save tokens.
 */
enum class PhoneTool(val slug: String, val brief: String) {
    SearchContext("search_context", "search_context(query, days?): find words in what the user saw on screen and in their notifications, the last `days` days (default 7)"),
    InboxSince("inbox_since", "inbox_since(time): messages and notifications that came in since a time, as 14:30 or 3h"),
    DayTimeline("day_timeline", "day_timeline(date?): one day's apps, places and notifications; date is today, yesterday or 2026-10-27"),
    DeviceStatus("device_status", "device_status(): battery, network, Do not disturb, volume and the next alarm"),
    OpenApp("open_app", "open_app(app): open an app by its name"),
    SetAlarm("set_alarm", "set_alarm(hour, minute, label?): set an alarm, hour 0 to 23"),
    SetTimer("set_timer", "set_timer(seconds, label?): start a countdown timer"),
    SetDoNotDisturb("set_do_not_disturb", "set_do_not_disturb(on): turn Do not disturb on (true) or off (false)"),
    NowPlaying("now_playing", "now_playing(action?): what's playing; action is play, pause, next or previous"),
    ;

    companion object {
        fun of(slug: String): PhoneTool? = entries.firstOrNull { it.slug == slug }

        /**
         * True when the tool's result can hold text from outside: what the user saw, their messages, or a track's
         * title and artist. A tool not listed here counts as one. The acting tools' confirmations and device_status don't.
         */
        fun bringsOutsideText(tool: String): Boolean = when (of(tool)) {
            OpenApp, SetAlarm, SetTimer, SetDoNotDisturb, DeviceStatus -> false
            else -> true
        }

        /** True when the call changes something on the phone rather than reading it. A tool not listed here counts as acting. */
        fun acts(tool: String, arguments: JsonObject): Boolean = when (of(tool)) {
            SearchContext, InboxSince, DayTimeline, DeviceStatus -> false
            NowPlaying -> (arguments["action"] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true
            else -> true
        }
    }
}

/** How a tool step ended. */
enum class StepOutcome {
    Done,
    /** Handset said no: a source is switched off for this app, or the phone isn't recording it. */
    Refused,
    Failed,
}

/** The quiet line a tool step shows under the reply: what was done, in the past tense, or what couldn't be. */
fun stepLabel(tool: String, arguments: JsonObject, outcome: StepOutcome): String {
    fun s(key: String) = (arguments[key] as? JsonPrimitive)?.contentOrNull?.let(::short)
    val ok = outcome == StepOutcome.Done
    return when (PhoneTool.of(tool)) {
        PhoneTool.SearchContext -> s("query")?.let { if (ok) "Searched your phone for “$it”" else "Couldn't search your phone for “$it”" }
            ?: if (ok) "Searched your phone" else "Couldn't search your phone"
        PhoneTool.InboxSince -> s("time")?.let { if (ok) "Caught up on messages since $it" else "Couldn't catch up on messages since $it" }
            ?: if (ok) "Caught up on messages" else "Couldn't catch up on messages"
        PhoneTool.DayTimeline -> {
            val day = s("date")?.let { if (it == "today" || it == "yesterday") it else "on $it" } ?: "today"
            if (ok) "Looked at your day $day".replace("day today", "day today") else "Couldn't look at your day $day"
        }
        PhoneTool.DeviceStatus -> if (ok) "Checked the phone's status" else "Couldn't check the phone's status"
        PhoneTool.OpenApp -> s("app")?.let { if (ok) "Opened $it" else "Couldn't open $it" } ?: if (ok) "Opened an app" else "Couldn't open the app"
        PhoneTool.SetAlarm -> {
            val h = arguments["hour"]?.intValue()
            val m = arguments["minute"]?.intValue()
            val at = if (h != null && m != null) " for %02d:%02d".format(h, m) else ""
            if (ok) "Set an alarm$at" else "Couldn't set an alarm$at"
        }
        PhoneTool.SetTimer -> {
            val length = arguments["seconds"]?.intValue()?.let { " for ${duration(it)}" }.orEmpty()
            if (ok) "Started a timer$length" else "Couldn't start a timer$length"
        }
        PhoneTool.SetDoNotDisturb -> {
            val state = if (arguments["on"]?.boolValue() == false) "off" else "on"
            if (ok) "Turned Do not disturb $state" else "Couldn't turn Do not disturb $state"
        }
        PhoneTool.NowPlaying -> when (s("action")) {
            "play" -> if (ok) "Pressed play" else "Couldn't press play"
            "pause" -> if (ok) "Paused what's playing" else "Couldn't pause"
            "toggle" -> if (ok) "Paused or resumed what's playing" else "Couldn't pause or resume"
            "next" -> if (ok) "Skipped to the next track" else "Couldn't skip"
            "previous" -> if (ok) "Went back a track" else "Couldn't go back a track"
            else -> if (ok) "Checked what's playing" else "Couldn't check what's playing"
        }
        null -> if (ok) "Used $tool" else "Couldn't use $tool"
    }
}

/**
 * Handset words a refusal for the agent ("Ask the user to turn on Notifications for Nano Chat in Handset's Settings,
 * under Connected agents."). This rewords it for the person reading the chat.
 */
fun refusalForPerson(message: String): String =
    message.replace("Ask the user to turn", "Turn").replace("Ask the user to", "Please").trim()

/** True when Handset's error says something is switched off, rather than that the tool failed. */
fun isRefusal(message: String): Boolean =
    ("access is off" in message) || ("isn't recording" in message) || ("Ask the user to turn on" in message)

private fun short(text: String) = if (text.length > 32) text.take(32).trimEnd() + "…" else text

private fun duration(seconds: Int): String = when {
    seconds % 3600 == 0 -> "${seconds / 3600} h"
    seconds >= 3600 -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
    seconds % 60 == 0 -> "${seconds / 60} min"
    seconds > 60 -> "${seconds / 60} min ${seconds % 60} s"
    else -> "$seconds s"
}
