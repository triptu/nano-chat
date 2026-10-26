package ai.tushar.nanochat

import ai.tushar.nanochat.data.ChatDatabase
import ai.tushar.nanochat.data.starterAgents
import ai.tushar.nanochat.attachments.AttachmentStore
import ai.tushar.nanochat.chat.ComposerDrafts
import ai.tushar.nanochat.litert.LiteRtBackend
import ai.tushar.nanochat.litert.LiteRtCatalog
import ai.tushar.nanochat.model.ChatEngine
import ai.tushar.nanochat.model.FakeBackend
import ai.tushar.nanochat.model.MlKitBackend
import ai.tushar.nanochat.model.ModelId
import ai.tushar.nanochat.model.Models
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
    val attachments = AttachmentStore(context)
    val models = Models(
        real = ModelId.entries.associateWith { id ->
            if (id == ModelId.Nano) MlKitBackend(context) else LiteRtBackend(context, LiteRtCatalog.getValue(id))
        },
        fakes = ModelId.entries.associateWith { FakeBackend(model = it) },
        allowAutoFake = BuildConfig.DEBUG,
        settings = settings,
        scope = scope,
    )
    val engine = ChatEngine(dao, models, settings, attachments, scope)
    val drafts = ComposerDrafts()

    /** Deletes photo and voice note files that no message or draft needs any more. */
    suspend fun cleanUpAttachments() = attachments.deleteUnreferenced(dao.attachmentFiles().toSet(), keep = drafts.files())
}

class NanoChatApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.scope.launch {
            if (graph.dao.agentCount() == 0) graph.dao.insertAgents(starterAgents(System.currentTimeMillis()))
            // Drafts live in memory, so files from drafts left behind when the app last closed are unreferenced now.
            graph.cleanUpAttachments()
        }
        graph.models.start()
    }
}

val Context.graph: AppGraph get() = (applicationContext as NanoChatApp).graph
