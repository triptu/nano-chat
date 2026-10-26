package ai.tushar.nanochat.devtools

import ai.tushar.nanochat.gate.bytesText
import ai.tushar.nanochat.model.BackendKind
import ai.tushar.nanochat.model.MediaSupport
import ai.tushar.nanochat.model.ModelId
import ai.tushar.nanochat.model.ModelInfo
import ai.tushar.nanochat.model.ModelState

/** "3.7 GB · not downloaded", "downloading 34%", "downloaded · GPU", "built in · ready". */
fun modelStatusLine(id: ModelId, state: ModelState, info: ModelInfo): String {
    val builtIn = id == ModelId.Nano && info.backend == BackendKind.Real
    val main = when (state) {
        ModelState.Checking -> "checking…"
        ModelState.Downloadable -> listOfNotNull(info.downloadBytes?.let(::bytesText), "not downloaded").joinToString(" · ")
        is ModelState.Downloading -> "downloading " + percent(state)
        ModelState.Ready -> when {
            builtIn -> "built in · ready"
            info.loading -> "downloaded · loading"
            info.loaded && info.accelerator != null -> "downloaded · ${info.accelerator}"
            else -> "downloaded"
        }
        ModelState.Unsupported -> "not supported on this phone"
        is ModelState.Failed -> "failed"
    }
    return if (info.backend == BackendKind.Fake) "$main · fake" else main
}

fun percent(d: ModelState.Downloading): String =
    d.totalBytes?.takeIf { it > 0 }?.let { "${d.downloadedBytes * 100 / it}%" } ?: bytesText(d.downloadedBytes)

/** "photos (up to 4), voice notes", "one photo", "text only". */
fun mediaText(m: MediaSupport): String = listOfNotNull(
    when (m.maxImages) {
        0 -> null
        1 -> "one photo"
        else -> "photos (up to ${m.maxImages})"
    },
    if (m.audio) "voice notes" else null,
).joinToString(", ").ifEmpty { "text only" }
