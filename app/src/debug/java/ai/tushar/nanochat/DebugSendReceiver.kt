package ai.tushar.nanochat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Debug builds only: sends a chat message without the keyboard, for scripted screenshots.
 * adb shell am broadcast -a ai.tushar.nanochat.debug.SEND -p ai.tushar.nanochat --es agent Nano --es text "Hi"
 */
class DebugSendReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra("agent") ?: return
        val text = intent.getStringExtra("text") ?: return
        val graph = context.graph
        val done = goAsync()
        graph.scope.launch {
            try {
                val agent = graph.dao.agentPreviews().first().firstOrNull { it.agent.name == name }?.agent ?: return@launch
                graph.engine.send(agent.id, text)
            } finally {
                done.finish()
            }
        }
    }
}
