package ai.tushar.nanochat

import ai.tushar.nanochat.attachments.DraftAttachment
import ai.tushar.nanochat.attachments.wavDurationMs
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/**
 * Debug builds only: sends a chat message without the keyboard, for scripted screenshots.
 * adb shell am broadcast -a ai.tushar.nanochat.debug.SEND -p ai.tushar.nanochat --es agent Nano --es text "Hi"
 *
 * Optional extras, each with or without text:
 * - `--es image <absolute path the app can read, or a content:// uri>` imports a photo and sends it.
 * - `--es audio <absolute path to a 16 kHz mono WAV>` copies it in as a voice note.
 */
class DebugSendReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra("agent") ?: return
        val text = intent.getStringExtra("text").orEmpty()
        val image = intent.getStringExtra("image")
        val audio = intent.getStringExtra("audio")
        val graph = context.graph
        val done = goAsync()
        graph.scope.launch {
            try {
                val agent = graph.dao.agentPreviews().first().firstOrNull { it.agent.name == name }?.agent ?: return@launch
                val attachments = buildList<DraftAttachment> {
                    if (image != null) {
                        val uri = if (image.startsWith("content://")) Uri.parse(image) else Uri.fromFile(File(image))
                        add(graph.attachments.importImage(uri))
                    }
                    if (audio != null) {
                        val wav = graph.attachments.newFile("wav")
                        File(audio).copyTo(wav, overwrite = true)
                        add(graph.attachments.audioDraft(wav, wavDurationMs(wav)))
                    }
                }
                graph.engine.send(agent.id, text, attachments)
            } catch (e: Exception) {
                Log.w("NanoChat", "Debug send failed", e)
            } finally {
                done.finish()
            }
        }
    }
}
