package ai.tushar.nanochat

import ai.tushar.nanochat.data.ChatDatabase
import ai.tushar.nanochat.data.starterAgents
import ai.tushar.nanochat.attachments.AttachmentStore
import ai.tushar.nanochat.chat.ComposerDrafts
import ai.tushar.nanochat.handset.HandsetLink
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
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * How long the app sits in the background before Gemma's memory is freed. With 4 to 5 GB held, Android's low-memory
 * killer picks this app first once it's in the background: on the Fold it was killed 39 s after going home.
 */
private const val ReleaseAfterBackgroundMs = 10_000L

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
    val handset = HandsetLink(context, BuildConfig.VERSION_NAME)
    val engine = ChatEngine(dao, models, settings, attachments, scope, handset = handset)
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
            graph.engine.recoverUnanswered()
        }
        graph.models.start()
        ProcessLifecycleOwner.get().lifecycle.addObserver(BackgroundRelease(graph))
    }
}

/** Frees the heavy model while the app is in the background, waiting for any reply in progress to finish first. */
private class BackgroundRelease(private val graph: AppGraph) : DefaultLifecycleObserver {
    private var release: Job? = null

    override fun onStop(owner: LifecycleOwner) {
        release = graph.scope.launch {
            do delay(ReleaseAfterBackgroundMs) while (!graph.models.releaseIfIdle())
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        release?.cancel()
    }
}

val Context.graph: AppGraph get() = (applicationContext as NanoChatApp).graph
