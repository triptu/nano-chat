package ai.tushar.nanochat

import ai.tushar.nanochat.data.ChatDatabase
import ai.tushar.nanochat.data.starterAgents
import ai.tushar.nanochat.model.ChatEngine
import ai.tushar.nanochat.model.FakeBackend
import ai.tushar.nanochat.model.MlKitBackend
import ai.tushar.nanochat.model.NanoModel
import ai.tushar.nanochat.settings.Settings
import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** App-wide objects. Built once in [NanoChatApp] and reached from screens through [Context.graph]. */
class AppGraph(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val database = ChatDatabase.open(context)
    val dao = database.chatDao()
    val settings = Settings(context)
    val model = NanoModel(MlKitBackend(context), FakeBackend(), allowAutoFake = BuildConfig.DEBUG, settings, scope)
    val engine = ChatEngine(dao, model, settings, scope)
}

class NanoChatApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.scope.launch {
            if (graph.dao.agentCount() == 0) graph.dao.insertAgents(starterAgents(System.currentTimeMillis()))
        }
        graph.model.start()
    }
}

val Context.graph: AppGraph get() = (applicationContext as NanoChatApp).graph
