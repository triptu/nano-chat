package ai.tushar.nanochat

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
 */
class DebugModelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = context.graph
        ModelId.parse(intent.getStringExtra("download"))?.let(graph.models::download)
        ModelId.parse(intent.getStringExtra("cancel"))?.let(graph.models::cancelDownload)
        if (intent.hasExtra("refresh")) graph.models.refresh()
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
                if (intent.hasExtra("model")) graph.dao.updateAgent(agent.copy(model = ModelId.parse(intent.getStringExtra("model"))?.name))
            } finally {
                done.finish()
            }
        }
    }
}
