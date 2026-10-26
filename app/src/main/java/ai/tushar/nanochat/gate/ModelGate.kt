package ai.tushar.nanochat.gate

import ai.tushar.nanochat.devtools.LocalDevTools
import ai.tushar.nanochat.model.ModelState
import ai.tushar.nanochat.ui.Ico
import ai.tushar.nanochat.ui.Icons
import ai.tushar.nanochat.ui.mascot.Blob
import ai.tushar.nanochat.ui.mascot.ThinkingDots
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.util.Locale

private val Coral = Color(0xFFFF8A6B)
private val Sleepy = Color(0xFFC8B8AC)

/** Full-screen stand-in while the model isn't usable: checking, downloading, or not supported. */
@Composable
fun ModelGate(state: ModelState, onDownload: () -> Unit, onRetry: () -> Unit, onUseFake: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 420.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                Blob(if (state == ModelState.Unsupported || state is ModelState.Failed) Sleepy else Coral, 132.dp)
                Spacer(Modifier.height(24.dp))
                when (state) {
                    ModelState.Checking, ModelState.Ready -> {
                        Title("Waking Nano up")
                        Spacer(Modifier.height(16.dp))
                        ThinkingDots(Coral)
                    }
                    ModelState.Downloadable -> {
                        Title("One quick download")
                        Body("Nano Chat runs Gemini Nano right on your phone, so your chats stay private. The model downloads once, then works offline.")
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = onDownload) {
                            Ico(Icons.Download, null, size = 20.dp)
                            Spacer(Modifier.size(8.dp))
                            Text("Download Gemini Nano")
                        }
                    }
                    is ModelState.Downloading -> {
                        Title("Downloading Gemini Nano")
                        Body("You can leave this screen open. It takes a few minutes on Wi-Fi.")
                        Spacer(Modifier.height(24.dp))
                        val total = state.totalBytes
                        if (total != null && total > 0) {
                            LinearProgressIndicator(
                                progress = { (state.downloadedBytes.toFloat() / total).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(8.dp),
                                strokeCap = StrokeCap.Round,
                                drawStopIndicator = {},
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "${state.downloadedBytes * 100 / total}% · ${mb(state.downloadedBytes)} of ${mb(total)}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth().height(8.dp), strokeCap = StrokeCap.Round)
                            if (state.downloadedBytes > 0) {
                                Spacer(Modifier.height(10.dp))
                                Text(mb(state.downloadedBytes), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    ModelState.Unsupported -> {
                        Title("This phone can't run Gemini Nano")
                        Body("Nano Chat needs a phone with Google AICore and Gemini Nano, like recent Pixel and Galaxy phones. If this phone was just set up, AICore may still be getting ready.")
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = onRetry) { Text("Check again") }
                        if (LocalDevTools.current) TextButton(onClick = onUseFake) { Text("Use the fake model") }
                    }
                    is ModelState.Failed -> {
                        Title("Nano couldn't get ready")
                        Body(state.message)
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = onRetry) { Text("Try again") }
                        if (LocalDevTools.current) TextButton(onClick = onUseFake) { Text("Use the fake model") }
                    }
                }
            }
        }
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

private fun mb(bytes: Long): String =
    if (bytes >= 1_000_000_000) String.format(Locale.US, "%.2f GB", bytes / 1e9) else "${bytes / 1_000_000} MB"
