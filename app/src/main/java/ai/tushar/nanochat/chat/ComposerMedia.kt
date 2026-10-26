package ai.tushar.nanochat.chat

import ai.tushar.nanochat.attachments.DraftAttachment
import ai.tushar.nanochat.attachments.MaxVoiceNoteMs
import ai.tushar.nanochat.attachments.Recording
import ai.tushar.nanochat.data.AttachmentKind
import ai.tushar.nanochat.ui.Ico
import ai.tushar.nanochat.ui.Icons
import ai.tushar.nanochat.ui.theme.LocalChatColors
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

/** What the attach menu may offer for this chat's model. */
data class AttachOptions(
    val photosLeft: Int,
    val maxPhotos: Int,
    /** Null when the model can't hear audio; false when this message already has its voice note. */
    val voiceNote: Boolean?,
)

/** Which permission the user turned down, so the composer can say why it's needed. */
enum class Denied(val message: String) {
    Camera("Nano Chat needs the camera to take photos. You can allow it in Settings."),
    Mic("Nano Chat needs the microphone to record voice notes. You can allow it in Settings."),
}

private fun Context.granted(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/**
 * The "+" left of the message field: Photo (system photo picker, no storage permission), Camera, and Voice note.
 * Camera and microphone permissions are asked for only when first used.
 */
@Composable
fun AttachButton(
    options: AttachOptions,
    onPhotos: (List<Uri>, then: () -> Unit) -> Unit,
    onRecord: () -> Unit,
    onDenied: (Denied) -> Unit,
) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    // Survives the camera app pushing this activity out of memory.
    var cameraFile by rememberSaveable { mutableStateOf<String?>(null) }

    val pickOne = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) onPhotos(listOf(uri)) {} }
    val pickMany = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(options.maxPhotos.coerceAtLeast(2))) { uris ->
        if (uris.isNotEmpty()) onPhotos(uris) {}
    }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val file = cameraFile?.let(::File)
        cameraFile = null
        if (file == null) return@rememberLauncherForActivityResult
        if (saved && file.length() > 0) onPhotos(listOf(Uri.fromFile(file))) { file.delete() } else file.delete()
    }
    fun launchCamera() {
        val file = File(context.cacheDir, "camera").apply { mkdirs() }.resolve("${UUID.randomUUID()}.jpg")
        cameraFile = file.path
        takePicture.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", file))
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) launchCamera() else onDenied(Denied.Camera)
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) onRecord() else onDenied(Denied.Mic)
    }

    Box {
        IconButton(
            onClick = { open = true },
            modifier = Modifier.size(48.dp),
            colors = if (open) IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest) else IconButtonDefaults.iconButtonColors(),
        ) { Ico(Icons.Attach, "Add a photo or voice note", size = 24.dp) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val photos = options.photosLeft > 0
            DropdownMenuItem(
                text = { Text("Photo") },
                leadingIcon = { Ico(Icons.Photo, null, size = 20.dp) },
                enabled = photos,
                onClick = {
                    open = false
                    val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    if (options.photosLeft > 1) pickMany.launch(request) else pickOne.launch(request)
                },
            )
            DropdownMenuItem(
                text = { Text("Camera") },
                leadingIcon = { Ico(Icons.Camera, null, size = 20.dp) },
                enabled = photos,
                onClick = {
                    open = false
                    if (context.granted(Manifest.permission.CAMERA)) launchCamera() else cameraPermission.launch(Manifest.permission.CAMERA)
                },
            )
            if (options.voiceNote != null) {
                DropdownMenuItem(
                    text = { Text("Voice note") },
                    leadingIcon = { Ico(Icons.Mic, null, size = 20.dp) },
                    enabled = options.voiceNote,
                    onClick = {
                        open = false
                        if (context.granted(Manifest.permission.RECORD_AUDIO)) onRecord() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    },
                )
            }
        }
    }
}

/** Photos and voice notes waiting to be sent, above the message field. Each has its own remove button. */
@Composable
fun DraftStrip(drafts: List<DraftAttachment>, fileOf: (String) -> File, onRemove: (DraftAttachment) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 8.dp, end = 8.dp, top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        drafts.forEach { d ->
            when (d.kind) {
                AttachmentKind.Image -> DraftPhoto(d, fileOf, onRemove)
                AttachmentKind.Audio -> DraftVoiceNote(d, onRemove)
            }
        }
    }
}

private val DraftPhotoSize = 64.dp

@Composable
private fun DraftPhoto(d: DraftAttachment, fileOf: (String) -> File, onRemove: (DraftAttachment) -> Unit) {
    val px = with(LocalDensity.current) { DraftPhotoSize.roundToPx() }
    val bitmap = rememberPhoto(fileOf(d.file), px)
    Box(Modifier.size(DraftPhotoSize).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        if (bitmap != null) Image(bitmap, "Photo to send", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        RemoveBadge("Remove photo", Modifier.align(Alignment.TopEnd)) { onRemove(d) }
    }
}

@Composable
private fun DraftVoiceNote(d: DraftAttachment, onRemove: (DraftAttachment) -> Unit) {
    val ms = d.durationMs ?: 0
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.height(40.dp).semantics(mergeDescendants = true) { contentDescription = "Voice note to send, ${clock(ms)}" },
    ) {
        Row(Modifier.padding(start = 12.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Ico(Icons.Waveform, null, size = 18.dp)
            Spacer(Modifier.width(6.dp))
            Text(clock(ms), style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"))
            IconButton(onClick = { onRemove(d) }, modifier = Modifier.size(36.dp)) { Ico(Icons.Close, "Remove voice note", size = 16.dp) }
        }
    }
}

@Composable
private fun RemoveBadge(label: String, modifier: Modifier, onClick: () -> Unit) {
    // A 32 dp touch target around a small dark dot, so it stays tappable without covering the photo.
    IconButton(onClick = onClick, modifier = modifier.size(32.dp)) {
        Box(Modifier.size(20.dp).background(Color(0xB3000000), CircleShape), contentAlignment = Alignment.Center) {
            Ico(Icons.Close, label, size = 12.dp, tint = Color.White)
        }
    }
}

/**
 * Takes the message field's place while a voice note records: cancel, a timer with a live level meter, stop (keep it
 * as a draft to add words), and send.
 */
@Composable
fun RecordingBar(recording: Recording, onCancel: () -> Unit, onStop: () -> Unit, onSend: () -> Unit) {
    val colors = LocalChatColors.current
    val levels = remember { mutableStateListOf<Float>() }
    LaunchedEffect(recording.elapsedMs) {
        levels.add(recording.level)
        if (levels.size > LevelBars) levels.removeAt(0)
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCancel, modifier = Modifier.size(48.dp)) { Ico(Icons.Delete, "Cancel voice note", size = 22.dp) }
        Surface(
            color = colors.agentBubble,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.weight(1f).height(48.dp),
        ) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.error, CircleShape))
                Spacer(Modifier.width(10.dp))
                Text(
                    "${clock(recording.elapsedMs)} / ${clock(MaxVoiceNoteMs)}",
                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                    color = colors.onAgentBubble,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite; contentDescription = "Recording, ${clock(recording.elapsedMs)}" },
                )
                Spacer(Modifier.width(12.dp))
                LevelMeter(levels, MaterialTheme.colorScheme.primary, Modifier.weight(1f).height(24.dp).clearAndSetSemantics {})
            }
        }
        IconButton(onClick = onStop, modifier = Modifier.size(48.dp)) { Ico(Icons.Stop, "Stop recording", size = 18.dp) }
        FilledIconButton(
            onClick = onSend,
            modifier = Modifier.size(48.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = colors.userBubble, contentColor = colors.onUserBubble),
        ) { Ico(Icons.Send, "Send voice note", size = 22.dp) }
    }
}

private const val LevelBars = 28

/** The newest loudness readings as bars, newest on the right. */
@Composable
private fun LevelMeter(levels: List<Float>, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val bar = 3.dp.toPx()
        val step = size.width / LevelBars
        val start = size.width - levels.size * step
        levels.forEachIndexed { i, l ->
            val h = (size.height * (0.12f + 0.88f * l)).coerceAtLeast(bar)
            drawRoundRect(
                color,
                topLeft = Offset(start + i * step + (step - bar) / 2, (size.height - h) / 2),
                size = Size(bar, h),
                cornerRadius = CornerRadius(bar / 2),
            )
        }
    }
}

/** A short line under the chat when the user turned down a permission, with a way to fix it. */
@Composable
fun PermissionCard(denied: Denied, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(denied.message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 6.dp))
            TextButton(onClick = {
                onDismiss()
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
            }) { Text("Settings") }
            IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) { Ico(Icons.Close, "Dismiss", size = 18.dp) }
        }
    }
}
