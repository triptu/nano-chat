package ai.tushar.nanochat.devtools

import ai.tushar.nanochat.data.Generation
import ai.tushar.nanochat.model.BackendKind
import ai.tushar.nanochat.model.MediaBudgetShare
import ai.tushar.nanochat.model.ModelId
import ai.tushar.nanochat.model.ModelInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatInternalsSheet(generation: Generation?, info: ModelInfo, infos: Map<ModelId, ModelInfo>, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Chat internals", style = MaterialTheme.typography.titleLarge)
            Fact("Model now", info.id.label + (if (info.backend == BackendKind.Fake) " (fake)" else "") + (info.baseModelName?.let { " · $it" } ?: ""))
            if (generation == null) {
                Text("Nothing sent yet. Send a message and the exact prompt shows up here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            val genModel = ModelId.parse(generation.model)
            val fake = generation.backend == BackendKind.Fake.name
            Fact(
                "Last reply from",
                listOfNotNull(genModel?.label ?: "unknown model", if (fake) "fake" else null, generation.accelerator).joinToString(" · "),
            )
            generation.loadMs?.let { Fact("Model load", "${it} ms, before this reply") }
            if (generation.prefillTokensPerSecond != null || generation.decodeTokensPerSecond != null) {
                Fact(
                    "Speed",
                    listOfNotNull(
                        generation.prefillTokensPerSecond?.let { "prefill ${it.roundToInt()} tok/s" },
                        generation.decodeTokensPerSecond?.let { "decode ${it.roundToInt()} tok/s" },
                    ).joinToString(" · "),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Input tokens", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text("${generation.inputTokens} of ${generation.inputBudget} budget", style = MaterialTheme.typography.labelLarge)
                }
                LinearProgressIndicator(
                    progress = { (generation.inputTokens.toFloat() / generation.inputBudget.coerceAtLeast(1)).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    strokeCap = StrokeCap.Round,
                    drawStopIndicator = {},
                )
            }
            Fact("Turns included", "${generation.includedTurns}, dropped ${generation.droppedTurns}")
            val nativeChat = genModel?.let { infos[it]?.nativeChat } == true
            Fact(
                "Sent as",
                when {
                    nativeChat -> "chat turns, with the personality as a real system instruction"
                    generation.usedSystemInstruction -> "one transcript, with the personality as a system instruction"
                    generation.usedPrefixCache -> "one transcript, with the personality at the start as a cached prefix (no system instructions)"
                    else -> "one transcript, with the personality at the start (no system instructions)"
                },
            )
            MediaFacts(generation)
            Fact("Params", "temperature ${fmt(generation.temperature)} · topK ${generation.topK} · max ${generation.maxOutputTokens}")
            Fact(
                "Timing",
                listOfNotNull(
                    generation.ttftMs?.let { "first token ${it} ms" },
                    "total ${generation.totalMs} ms",
                    "${generation.outputTokens} output tokens",
                ).joinToString(" · "),
            )
            Fact("Finish reason", generation.finishReason ?: "none")
            generation.error?.let { Fact("Error", it) }
            if (generation.usedSystemInstruction) Raw("System instruction", generation.systemPrompt)
            Raw(if (nativeChat) "Turns (as text)" else "Prompt", generation.prompt)
        }
    }
}

/** Photos and voice notes in the request, and the ones the window left out. Shown only when there were any. */
@Composable
private fun MediaFacts(g: Generation) {
    if (g.imagesSent + g.imagesDropped + g.audioSent + g.audioDropped == 0) return
    if (g.imagesSent + g.imagesDropped > 0) Fact("Photos", mediaCount(g.imagesSent, g.imagesDropped))
    if (g.audioSent + g.audioDropped > 0) Fact("Voice notes", mediaCount(g.audioSent, g.audioDropped))
    Text(
        "Older photos and voice notes are left out once they'd use more than ${(MediaBudgetShare * 100).roundToInt()}% of the input budget. " +
            "Gemini Nano sees only the newest photo.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun mediaCount(sent: Int, dropped: Int) = "$sent sent" + if (dropped > 0) ", $dropped left out (older)" else ""

@Composable
internal fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.42f))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.58f))
    }
}

@Composable
private fun Raw(label: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
            Text(
                text,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

internal fun fmt(f: Float) = String.format(Locale.US, "%.2f", f)
