package ai.tushar.nanochat.devtools

import ai.tushar.nanochat.data.Generation
import ai.tushar.nanochat.model.BackendKind
import ai.tushar.nanochat.model.InputTokenCap
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatInternalsSheet(generation: Generation?, info: ModelInfo, onDismiss: () -> Unit) {
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
            Fact("Model now", info.backend.label + (info.baseModelName?.let { " · $it" } ?: ""))
            if (generation == null) {
                Text("Nothing sent yet. Send a message and the exact prompt shows up here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            val sentWith = runCatching { BackendKind.valueOf(generation.backend) }.getOrNull()
            if (sentWith != null && sentWith != info.backend) Fact("Last reply came from", sentWith.label)

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Input tokens", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(
                        "${generation.inputTokens} of ${generation.inputBudget} (cap $InputTokenCap)",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                LinearProgressIndicator(
                    progress = { (generation.inputTokens.toFloat() / InputTokenCap).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    strokeCap = StrokeCap.Round,
                    drawStopIndicator = {},
                )
            }
            Fact("Turns included", "${generation.includedTurns}, dropped ${generation.droppedTurns}")
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
            Fact("System prompt", if (generation.usedSystemInstruction) "sent as a system instruction" else "inlined into the prompt (model has no system instructions)")
            if (generation.usedSystemInstruction) Raw("System instruction", generation.systemPrompt)
            Raw("Prompt", generation.prompt)
        }
    }
}

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
