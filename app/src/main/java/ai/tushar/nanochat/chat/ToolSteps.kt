package ai.tushar.nanochat.chat

import ai.tushar.nanochat.data.ToolStep
import ai.tushar.nanochat.handset.HandsetApp
import ai.tushar.nanochat.handset.StepOutcome
import ai.tushar.nanochat.ui.Ico
import ai.tushar.nanochat.ui.Icons
import ai.tushar.nanochat.ui.theme.LocalChatColors
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * One tool step, as a quiet line under the reply: what was done, or what couldn't be. [detail] shows under it for a
 * refusal, so the person sees what to turn on in Handset without opening anything.
 */
@Composable
internal fun StepLine(label: String, outcome: StepOutcome, cut: Boolean, running: Boolean, detail: String?, onClick: (() -> Unit)?) {
    val colors = LocalChatColors.current
    val problem = outcome != StepOutcome.Done
    val tint = if (problem) MaterialTheme.colorScheme.onSurfaceVariant else colors.faint
    Column(
        Modifier
            .padding(start = AvatarGutter, top = 2.dp)
            .clip(MaterialTheme.shapes.small)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = "See what came back", onClick = onClick) else Modifier)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Ico(if (problem) Icons.Warning else Icons.Phone, null, size = 14.dp, tint = tint)
            Spacer(Modifier.width(6.dp))
            Text(
                if (cut && !running) "$label · cut to fit" else label,
                style = MaterialTheme.typography.labelMedium,
                color = tint,
            )
        }
        detail?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, top = 2.dp),
            )
        }
    }
}

/** What a step sent and what came back, opened by tapping its line. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StepSheet(step: ToolStep, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val outcome = StepOutcome.entries.firstOrNull { it.name == step.outcome } ?: StepOutcome.Failed
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(step.label, style = MaterialTheme.typography.titleMedium)
            if (step.cut) {
                Text(
                    "This was too long for the model's context, so it read the first ${step.sentChars} of ${step.result.length} characters.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(if (outcome == StepOutcome.Done) "What came back" else "What Handset said", style = MaterialTheme.typography.titleSmall)
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                SelectionContainer {
                    Text(
                        step.result.ifBlank { "Nothing" },
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                    )
                }
            }
            if (outcome == StepOutcome.Refused) {
                TextButton(
                    onClick = { context.packageManager.getLaunchIntentForPackage(HandsetApp.Package)?.let(context::startActivity) },
                    modifier = Modifier.align(Alignment.End),
                ) { Text("Open Handset") }
            }
        }
    }
}
