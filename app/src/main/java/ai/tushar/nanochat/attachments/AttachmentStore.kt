package ai.tushar.nanochat.attachments

import ai.tushar.nanochat.data.Attachment
import ai.tushar.nanochat.data.AttachmentKind
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/** A photo or voice note saved to app storage but not sent yet. File names are relative to [AttachmentStore.dir]. */
data class DraftAttachment(
    val kind: AttachmentKind,
    val file: String,
    val modelFile: String,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
) {
    fun toAttachment(messageId: Long) = Attachment(
        messageId = messageId, kind = kind, file = file, modelFile = modelFile, width = width, height = height, durationMs = durationMs,
    )
}

/** Longest side of the photo copy sent to the model. Gemma 4's vision encoder works at about this size, and Nano's too. */
const val ModelImageMaxSide = 768

/** Longest side of the photo copy shown in the chat. */
private const val DisplayImageMaxSide = 2048

/** Photos and voice notes live in app-private storage (`files/attachments`), never in shared storage. */
class AttachmentStore(context: Context) {
    val dir: File = File(context.filesDir, "attachments").apply { mkdirs() }
    private val resolver = context.contentResolver

    fun file(name: String): File = File(dir, name)

    /** A fresh file for a new attachment, e.g. a voice note being recorded. */
    fun newFile(extension: String): File = File(dir, "${UUID.randomUUID()}.$extension")

    /**
     * Copies a picked or captured photo into app storage: a display copy (JPEG, at most [DisplayImageMaxSide])
     * and a model copy (PNG, at most [ModelImageMaxSide]).
     */
    suspend fun importImage(uri: Uri): DraftAttachment = withContext(Dispatchers.IO) {
        val source = ImageDecoder.createSource(resolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val side = max(info.size.width, info.size.height)
            if (side > DisplayImageMaxSide) {
                val scale = DisplayImageMaxSide.toFloat() / side
                decoder.setTargetSize((info.size.width * scale).roundToInt(), (info.size.height * scale).roundToInt())
            }
        }
        val display = newFile("jpg")
        display.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val small = scaleToFit(bitmap, ModelImageMaxSide)
        val model = newFile("png")
        model.outputStream().use { small.compress(Bitmap.CompressFormat.PNG, 100, it) }
        DraftAttachment(AttachmentKind.Image, display.name, model.name, bitmap.width, bitmap.height)
    }

    /** A recorded voice note (16 kHz mono WAV) becomes a draft; the same file is played back and sent to the model. */
    fun audioDraft(wav: File, durationMs: Long) = DraftAttachment(AttachmentKind.Audio, wav.name, wav.name, durationMs = durationMs)

    /** Deletes a draft the user removed before sending. */
    fun discard(d: DraftAttachment) {
        file(d.file).delete()
        file(d.modelFile).delete()
    }

    /** Deletes files no message refers to any more (after "Clear chat", a deleted companion, or abandoned drafts). */
    suspend fun deleteUnreferenced(referenced: Set<String>, keep: Set<String> = emptySet()) = withContext(Dispatchers.IO) {
        dir.listFiles()?.forEach { f -> if (f.name !in referenced && f.name !in keep) f.delete() }
    }
}

private fun scaleToFit(b: Bitmap, maxSide: Int): Bitmap {
    val side = max(b.width, b.height)
    if (side <= maxSide) return b
    val scale = maxSide.toFloat() / side
    return Bitmap.createScaledBitmap(b, (b.width * scale).roundToInt(), (b.height * scale).roundToInt(), true)
}
