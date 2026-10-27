package ai.tushar.nanochat

import ai.tushar.nanochat.handset.HandsetConnection
import ai.tushar.nanochat.model.ModelId
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Debug builds only: model chores without the UI, for scripted tests on a phone.
 * adb shell am broadcast -a ai.tushar.nanochat.debug.MODEL -p ai.tushar.nanochat --es download GemmaE4B
 * adb shell am broadcast -a ai.tushar.nanochat.debug.MODEL -p ai.tushar.nanochat --es agent Basil --es model GemmaE4B
 * adb shell am broadcast -a ai.tushar.nanochat.debug.MODEL -p ai.tushar.nanochat --es clear Basil
 * adb shell am broadcast -a ai.tushar.nanochat.debug.MODEL -p ai.tushar.nanochat --es agent Basil --es copyAs "Basil G" --es model GemmaE4B
 *
 * Handset, for when its connect screen can't be used: save a token Handset showed, and turn a companion's switch on.
 * adb shell am broadcast -a ai.tushar.nanochat.debug.MODEL -p ai.tushar.nanochat --es handsetToken hs_... --es handsetAgent <id>
 * adb shell am broadcast -a ai.tushar.nanochat.debug.MODEL -p ai.tushar.nanochat --es agent Nano --ez useHandset true
 */
class DebugModelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = context.graph
        ModelId.parse(intent.getStringExtra("download"))?.let(graph.models::download)
        ModelId.parse(intent.getStringExtra("cancel"))?.let(graph.models::cancelDownload)
        if (intent.hasExtra("refresh")) graph.models.refresh()
        intent.getStringExtra("handsetToken")?.let { token ->
            val url = intent.getStringExtra("handsetUrl") ?: "http://127.0.0.1:8765/mcp"
            graph.handset.save(HandsetConnection(url, token, intent.getStringExtra("handsetAgent") ?: "debug"))
        }
        val agentName = intent.getStringExtra("agent") ?: intent.getStringExtra("clear") ?: return
        val done = goAsync()
        graph.scope.launch {
            try {
                val agent = graph.dao.agentPreviews().first().firstOrNull { it.agent.name == agentName }?.agent ?: return@launch
                if (intent.hasExtra("clear")) graph.dao.clearMessages(agent.id)
                intent.getStringExtra("copyAs")?.let { name ->
                    val model = ModelId.parse(intent.getStringExtra("model"))?.name
                    graph.dao.insertAgent(agent.copy(id = 0, name = name, model = model, createdAt = System.currentTimeMillis()))
                    return@launch
                }
                if (intent.hasExtra("useHandset")) graph.dao.updateAgent(agent.copy(useHandset = intent.getBooleanExtra("useHandset", false)))
                if (intent.hasExtra("model")) graph.dao.updateAgent(agent.copy(model = ModelId.parse(intent.getStringExtra("model"))?.name))
            } finally {
                done.finish()
            }
        }
    }
}
