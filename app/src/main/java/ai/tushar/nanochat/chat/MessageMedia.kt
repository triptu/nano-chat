package ai.tushar.nanochat.chat

import ai.tushar.nanochat.data.Attachment
import ai.tushar.nanochat.ui.Ico
import ai.tushar.nanochat.ui.Icons
import ai.tushar.nanochat.ui.theme.LocalChatColors
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

private val PhotoRadius = 16.dp
private val PhotoMaxHeight = 320.dp
private val PhotoMaxWidth = 280.dp
private val PhotoGap = 3.dp

/** Decoded photos, keyed by file and size, so scrolling back up doesn't decode again. About 32 MB. */
private val bitmaps = object : LruCache<String, ImageBitmap>(32 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}

/** Decodes [file] off the main thread, downsampled so its longest side is near [maxPx]. Null until ready or if it's unreadable. */
@Composable
fun rememberPhoto(file: File, maxPx: Int): ImageBitmap? {
    val key = "${file.path}@$maxPx"
    return produceState(bitmaps.get(key), key) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) { decodeDownsampled(file, maxPx) }?.also { bitmaps.put(key, it) }
    }.value
}

private fun decodeDownsampled(file: File, maxPx: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}

/** Photos on a sent message: one fits its own shape, several sit in a two-column grid of squares. */
@Composable
fun PhotoGroup(photos: List<Attachment>, fileOf: (String) -> File, maxWidth: Dp, onOpen: (Attachment) -> Unit) {
    val width = minOf(maxWidth, PhotoMaxWidth)
    val px = with(LocalDensity.current) { width.roundToPx() }
    if (photos.size == 1) {
        val p = photos.single()
        val aspect = aspectOf(p)
        // Fit inside width x max height, keeping the photo's shape.
        val w = minOf(width, PhotoMaxHeight * aspect)
        Photo(p, fileOf, px, Modifier.width(w).aspectRatio(aspect), onOpen)
    } else {
        val cell = (width - PhotoGap) / 2
        Column(verticalArrangement = Arrangement.spacedBy(PhotoGap)) {
            photos.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(PhotoGap)) {
                    row.forEach { p -> Photo(p, fileOf, px / 2, Modifier.size(cell), onOpen) }
                }
            }
        }
    }
}

private fun aspectOf(a: Attachment): Float {
    val w = a.width ?: return 1f
    val h = a.height ?: return 1f
    return (w.toFloat() / h).coerceIn(0.5f, 2.4f)
}

@Composable
private fun Photo(a: Attachment, fileOf: (String) -> File, maxPx: Int, modifier: Modifier, onOpen: (Attachment) -> Unit) {
    val bitmap = rememberPhoto(fileOf(a.file), maxPx)
    Box(
        modifier
            .clip(RoundedCornerShape(PhotoRadius))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClickLabel = "Open photo", role = Role.Image) { onOpen(a) }
            .semantics { contentDescription = "You sent a photo" },
    ) {
        if (bitmap != null) Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

/** A sent photo, full screen on a dark backdrop. Pinch to zoom, double-tap to zoom in or out, back or the X to close. */
@Composable
fun PhotoViewer(file: File, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler(onBack = onClose)
        val window = LocalWindowInfo.current.containerSize
        val bitmap = rememberPhoto(file, max(window.width, window.height).coerceAtLeast(1080))
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (bitmap != null) {
                Image(
                    bitmap,
                    "Your photo",
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                offset = if (scale == 1f) Offset.Zero else offset + pan
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = { if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f })
                        }
                        .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
                    contentScale = ContentScale.Fit,
                )
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp).align(Alignment.TopStart),
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0x66000000), contentColor = Color.White),
            ) { Ico(Icons.Close, "Close") }
        }
    }
}

/** Plays one voice note at a time for a chat screen. */
@Stable
class VoicePlayer {
    var playing by mutableStateOf<String?>(null)
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    private var player: MediaPlayer? = null

    fun toggle(file: File) {
        if (playing == file.name) { stop(); return }
        stop()
        val p = runCatching {
            MediaPlayer().apply {
                setDataSource(file.path)
                prepare()
                setOnCompletionListener { this@VoicePlayer.stop() }
                start()
            }
        }.getOrNull() ?: return
        player = p
        playing = file.name
    }

    /** Called every frame or so while something plays. */
    fun tick() {
        val p = player ?: return
        progress = runCatching { p.currentPosition.toFloat() / p.duration.coerceAtLeast(1) }.getOrDefault(0f)
    }

    fun stop() {
        player?.release()
        player = null
        playing = null
        progress = 0f
    }
}

@Composable
fun rememberVoicePlayer(): VoicePlayer {
    val player = remember { VoicePlayer() }
    DisposableEffect(player) { onDispose { player.stop() } }
    LaunchedEffect(player.playing) {
        while (player.playing != null) { player.tick(); delay(50) }
    }
    return player
}

/** "0:12", "1:05". */
fun clock(ms: Long): String {
    val s = (ms / 1000.0).roundToInt()
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

private fun spokenLength(ms: Long): String {
    val s = (ms / 1000.0).roundToInt().coerceAtLeast(1)
    return if (s == 1) "1 second" else "$s seconds"
}

/** A sent voice note: play or pause, how far it has played, and its length. Styled like the user's bubble. */
@Composable
fun VoicePill(a: Attachment, file: File, player: VoicePlayer, shape: androidx.compose.ui.graphics.Shape) {
    val colors = LocalChatColors.current
    val playing = player.playing == file.name
    val ms = a.durationMs ?: 0
    Surface(
        color = colors.userBubble,
        contentColor = colors.onUserBubble,
        shape = shape,
        onClick = { player.toggle(file) },
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = "Voice note, ${spokenLength(ms)}"
            role = Role.Button
            onClick(label = if (playing) "Pause" else "Play") { player.toggle(file); true }
        },
    ) {
        Row(Modifier.padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).background(colors.onUserBubble, CircleShape), contentAlignment = Alignment.Center) {
                Ico(if (playing) Icons.Pause else Icons.Play, null, size = 14.dp, tint = colors.userBubble)
            }
            Spacer(Modifier.width(10.dp))
            LinearProgressIndicator(
                progress = { if (playing) player.progress else 0f },
                modifier = Modifier.width(110.dp).height(4.dp).clearAndSetSemantics {},
                color = colors.onUserBubble,
                trackColor = colors.onUserBubble.copy(alpha = 0.22f),
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
            Spacer(Modifier.width(10.dp))
            Text(clock(ms), style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"))
        }
    }
}
