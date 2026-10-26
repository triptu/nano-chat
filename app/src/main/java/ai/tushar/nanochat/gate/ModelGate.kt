package ai.tushar.nanochat.gate

import ai.tushar.nanochat.devtools.LocalDevTools
import ai.tushar.nanochat.model.BackendKind
import ai.tushar.nanochat.model.ModelId
import ai.tushar.nanochat.model.ModelInfo
import ai.tushar.nanochat.model.ModelState
import ai.tushar.nanochat.ui.Ico
import ai.tushar.nanochat.ui.Icons
import ai.tushar.nanochat.ui.mascot.Blob
import ai.tushar.nanochat.ui.mascot.ThinkingDots
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.util.Locale

private val Sleepy = Color(0xFFC8B8AC)

/** What the gate can do. Each runs for the gate's own model unless it says otherwise. */
data class GateActions(
    val onDownload: () -> Unit,
    val onCancel: () -> Unit,
    val onRetry: () -> Unit,
    /** Switches the companion to the other model. */
    val onUseOther: (ModelId) -> Unit,
    val onUseFake: () -> Unit,
)

/**
 * Stands in for a chat while its companion's model isn't usable: checking, needs a download, downloading,
 * failed, or not supported on this phone. Names the companion and the model so it's clear what's missing.
 */
@Composable
fun ModelGate(agentName: String, tint: Color, id: ModelId, state: ModelState, info: ModelInfo, actions: GateActions, modifier: Modifier = Modifier) {
    val dev = LocalDevTools.current
    val other = ModelId.entries.firstOrNull { it != id }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            val stuck = state == ModelState.Unsupported || state is ModelState.Failed
            Blob(if (stuck) Sleepy else tint, 112.dp)
            Spacer(Modifier.height(20.dp))
            Text(
                "$agentName uses ${id.label}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            when (state) {
                ModelState.Checking, ModelState.Ready -> {
                    Title("Getting ${id.label} ready")
                    Spacer(Modifier.height(16.dp))
                    ThinkingDots(tint)
                }
                ModelState.Downloadable -> {
                    Title("One quick download")
                    Body(downloadBlurb(id, info.downloadBytes))
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = actions.onDownload) {
                        Ico(Icons.Download, null, size = 20.dp)
                        Spacer(Modifier.size(8.dp))
                        Text("Download ${id.label}")
                    }
                }
                is ModelState.Downloading -> {
                    // ML Kit downloads Gemini Nano inside Android's AICore, which can't be stopped from here.
                    val cancellable = !(id == ModelId.Nano && info.backend == BackendKind.Real)
                    Title("Downloading ${id.label}")
                    Body(
                        "You can leave this chat. The download keeps going while Nano Chat is open." +
                            if (cancellable) " If you cancel, it picks up where it left off." else "",
                    )
                    Spacer(Modifier.height(24.dp))
                    DownloadProgress(state)
                    if (cancellable) {
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(onClick = actions.onCancel) { Text("Cancel") }
                    }
                }
                ModelState.Unsupported -> {
                    Title("This phone can't run ${id.label}")
                    Body(
                        if (id == ModelId.Nano) {
                            "Gemini Nano needs a phone with Google AICore, like recent Pixel and Galaxy phones. If this phone was just set up, AICore may still be getting ready."
                        } else {
                            "${id.label} needs a phone with more memory and storage than this one has."
                        },
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = actions.onRetry) { Text("Check again") }
                }
                is ModelState.Failed -> {
                    Title("${id.label} couldn't get ready")
                    Body(
                        if (id == ModelId.Nano) "Something stopped the model from loading. Check your connection and try again."
                        else "Something stopped the download. Check your connection and try again.",
                    )
                    if (dev) Body(state.message)
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = actions.onRetry) { Text("Try again") }
                }
            }
            if (state != ModelState.Checking && state != ModelState.Ready) {
                Spacer(Modifier.height(8.dp))
                if (other != null) TextButton(onClick = { actions.onUseOther(other) }) { Text("Use ${other.label} instead") }
                if (dev && (state == ModelState.Unsupported || state is ModelState.Failed)) {
                    TextButton(onClick = actions.onUseFake) { Text("Use the fake model") }
                }
            }
        }
    }
}

@Composable
private fun DownloadProgress(state: ModelState.Downloading) {
    val total = state.totalBytes
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, horizontalAlignment = Alignment.CenterHorizontally) {
        if (total != null && total > 0) {
            LinearProgressIndicator(
                progress = { (state.downloadedBytes.toFloat() / total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                strokeCap = StrokeCap.Round,
                drawStopIndicator = {},
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "${state.downloadedBytes * 100 / total}% · ${bytesText(state.downloadedBytes)} of ${bytesText(total)}",
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(8.dp), strokeCap = StrokeCap.Round)
            if (state.downloadedBytes > 0) {
                Spacer(Modifier.height(10.dp))
                Text(bytesText(state.downloadedBytes), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun downloadBlurb(id: ModelId, bytes: Long?): String {
    val sized = bytes?.let { "It's ${bytesText(it)} and downloads" } ?: "It downloads"
    return when (id) {
        ModelId.Nano -> "Gemini Nano is built into Android. $sized once, then works offline."
        else -> "$sized once, then works offline. Wi-Fi recommended."
    }
}

@Composable
private fun Title(text: String) =
    Text(text, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)

@Composable
private fun Body(text: String) {
    Spacer(Modifier.height(8.dp))
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
}

/** "840 MB", "1.2 GB", "3.7 GB". */
fun bytesText(bytes: Long): String =
    if (bytes >= 1_000_000_000) String.format(Locale.US, "%.1f GB", bytes / 1e9) else "${bytes / 1_000_000} MB"
