package ai.tushar.nanochat.devtools

import ai.tushar.nanochat.data.Message
import ai.tushar.nanochat.model.ModelId
import ai.tushar.nanochat.settings.GenerationParams
import ai.tushar.nanochat.ui.theme.LocalChatColors
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt

/** "Gemma 4 E4B · GPU · 0.41 s to first token · 32 tok/s · 128 tokens" under an agent reply. */
@Composable
fun ReplyStats(message: Message, modifier: Modifier = Modifier) {
    val parts = buildList {
        ModelId.parse(message.model)?.let { add(it.label) }
        message.accelerator?.let { add(it) }
        message.ttftMs?.let { add(String.format(Locale.US, "%.2f s to first token", it / 1000f)) }
        message.tokensPerSecond?.let { add("${it.roundToInt()} tok/s") }
        message.outputTokens?.let { add(if (it == 1) "1 token" else "$it tokens") }
    }
    if (parts.isEmpty()) return
    Text(
        parts.joinToString(" · "),
        style = MaterialTheme.typography.labelSmall,
        color = LocalChatColors.current.faint,
        modifier = modifier,
    )
}

@Composable
fun ParamSliders(params: GenerationParams, onChange: (GenerationParams) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LabeledSlider(
            "Temperature", String.format(Locale.US, "%.2f", params.temperature),
            value = params.temperature, range = GenerationParams.TemperatureRange,
        ) { onChange(params.copy(temperature = (it * 20).roundToInt() / 20f)) }
        LabeledSlider(
            "Top K", params.topK.toString(),
            value = params.topK.toFloat(),
            range = GenerationParams.TopKRange.first.toFloat()..GenerationParams.TopKRange.last.toFloat(),
        ) { onChange(params.copy(topK = it.roundToInt())) }
        LabeledSlider(
            "Max output tokens", params.maxOutputTokens.toString(),
            value = params.maxOutputTokens.toFloat(),
            range = GenerationParams.MaxOutputRange.first.toFloat()..GenerationParams.MaxOutputRange.last.toFloat(),
        ) { onChange(params.copy(maxOutputTokens = ((it / 32).roundToInt() * 32).coerceIn(GenerationParams.MaxOutputRange))) }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.semantics { contentDescription = label; stateDescription = valueText },
        )
    }
}

/** Per-agent overrides inside the agent editor. Off means the agent follows the defaults on the nerds page. */
@Composable
fun AgentParamsSection(
    temperature: Float?,
    topK: Int?,
    maxOutputTokens: Int?,
    defaults: GenerationParams,
    onChange: (Float?, Int?, Int?) -> Unit,
) {
    val custom = temperature != null || topK != null || maxOutputTokens != null
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.toggleable(
                value = custom,
                role = Role.Switch,
                onValueChange = { on ->
                    if (on) onChange(defaults.temperature, defaults.topK, defaults.maxOutputTokens) else onChange(null, null, null)
                },
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Own model settings", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (custom) "Used only for this chat." else "Using the defaults from Stats & settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = custom, onCheckedChange = null)
        }
        if (custom) {
            val p = GenerationParams(
                temperature ?: defaults.temperature,
                topK ?: defaults.topK,
                maxOutputTokens ?: defaults.maxOutputTokens,
            )
            ParamSliders(p) { onChange(it.temperature, it.topK, it.maxOutputTokens) }
        }
    }
}
