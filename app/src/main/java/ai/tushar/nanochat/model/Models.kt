package ai.tushar.nanochat.model

import ai.tushar.nanochat.settings.BackendChoice
import ai.tushar.nanochat.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource

sealed interface ModelState {
    data object Checking : ModelState
    /** The phone can't run this model (for Nano: no AICore, or AICore hasn't fetched its config yet). */
    data object Unsupported : ModelState
    data object Downloadable : ModelState
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long?) : ModelState
    data object Ready : ModelState
    data class Failed(val message: String) : ModelState
}

/** Facts about one model, for the dev tools. */
data class ModelInfo(
    val id: ModelId,
    val backend: BackendKind,
    val choice: BackendChoice,
    val realAvailability: Availability?,
    val media: MediaSupport = MediaSupport.TextOnly,
    val downloadBytes: Long? = null,
    val baseModelName: String? = null,
    val tokenLimit: Int? = null,
    val inputTokenCap: Int? = null,
    val systemPromptAvailable: Boolean = false,
    val nativeChat: Boolean = false,
    val cachingAvailable: Boolean = false,
    val aicoreVersion: String? = null,
    val lastWarmupMs: Long? = null,
    /** "GPU" or "CPU" once a LiteRT engine is loaded. */
    val accelerator: String? = null,
    val loadMs: Long? = null,
    val speculativeDecoding: Boolean? = null,
    val fileBytes: Long? = null,
    /** True while the engine is being loaded into memory. */
    val loading: Boolean = false,
    /** True while the engine holds memory in this app. Always false for Nano, which runs inside AICore. */
    val loaded: Boolean = false,
)

/**
 * Every model the app can talk through, and whether each is ready. "Auto" means the real models, except that
 * debug builds fall back to the fake one when a real model is unavailable (as Nano is on an emulator).
 * At most one heavy (LiteRT) engine is held in memory: [use] unloads the others first.
 */
class Models(
    private val real: Map<ModelId, ModelBackend>,
    private val fakes: Map<ModelId, FakeBackend>,
    private val allowAutoFake: Boolean,
    private val settings: Settings,
    private val scope: CoroutineScope,
) {
    private val _states = MutableStateFlow(ModelId.entries.associateWith<ModelId, ModelState> { ModelState.Checking })
    val states: StateFlow<Map<ModelId, ModelState>> = _states.asStateFlow()

    private val _infos = MutableStateFlow(ModelId.entries.associateWith { ModelInfo(it, BackendKind.Real, BackendChoice.Auto, null) })
    val infos: StateFlow<Map<ModelId, ModelInfo>> = _infos.asStateFlow()

    fun state(id: ModelId): Flow<ModelState> = states.map { it.getValue(id) }.distinctUntilChanged()
    fun info(id: ModelId): Flow<ModelInfo> = infos.map { it.getValue(id) }.distinctUntilChanged()

    private val current = ModelId.entries.associateWith { real.getValue(it) }.toMutableMap()
    private val resolveLock = Mutex()
    private val engineLock = Mutex()
    private val downloads = java.util.concurrent.ConcurrentHashMap<ModelId, Job>()
    private var loadedHeavy: ModelBackend? = null
    private var choice = BackendChoice.Auto

    /** The backend that runs [id] right now (real or fake). */
    fun backend(id: ModelId): ModelBackend = synchronized(current) { current.getValue(id) }

    fun start() {
        scope.launch {
            combine(settings.backendChoice, settings.fakeScenario) { c, s -> c to s }.collectLatest { (c, s) ->
                choice = c
                fakes.values.forEach { it.scenario = s }
                ModelId.entries.forEach { resolve(it) }
            }
        }
    }

    fun refresh(id: ModelId? = null) {
        scope.launch { (id?.let(::listOf) ?: ModelId.entries).forEach { resolve(it) } }
    }

    private fun setState(id: ModelId, s: ModelState) = _states.update { it + (id to s) }
    private fun setInfo(id: ModelId, f: (ModelInfo) -> ModelInfo) = _infos.update { it + (id to f(it.getValue(id))) }

    private suspend fun resolve(id: ModelId) = resolveLock.withLock {
        val realBackend = real.getValue(id)
        val realAvailability = runCatching { realBackend.availability() }.getOrNull()
        val picked: ModelBackend = when (choice) {
            BackendChoice.Real -> realBackend
            BackendChoice.Fake -> fakes.getValue(id)
            BackendChoice.Auto ->
                if (allowAutoFake && (realAvailability == null || realAvailability == Availability.Unavailable)) fakes.getValue(id) else realBackend
        }
        val previous = backend(id)
        if (picked !== previous) {
            downloads.remove(id)?.cancel()
            if (previous === loadedHeavy) engineLock.withLock { previous.unload(); loadedHeavy = null }
        }
        synchronized(current) { current[id] = picked }
        _infos.update {
            it + (id to ModelInfo(id, picked.kind, choice, realAvailability, media = picked.media, downloadBytes = picked.downloadBytes))
        }
        val availability = if (picked === realBackend) realAvailability else runCatching { picked.availability() }.getOrNull()
        when (availability) {
            Availability.Available -> ready(id)
            Availability.Downloadable -> if (downloads[id]?.isActive != true) setState(id, ModelState.Downloadable)
            Availability.Downloading -> download(id)
            Availability.Unavailable -> setState(id, ModelState.Unsupported)
            null -> setState(id, ModelState.Failed(if (id == ModelId.Nano) "Couldn't reach Google AICore" else "Couldn't check the model file"))
        }
    }

    fun download(id: ModelId) {
        if (downloads[id]?.isActive == true) return
        val source = backend(id)
        setState(id, ModelState.Downloading(0, source.downloadBytes))
        downloads[id] = scope.launch {
            var total: Long? = source.downloadBytes
            source.download().collect { e ->
                when (e) {
                    is DownloadEvent.Started -> { total = e.totalBytes ?: total; setState(id, ModelState.Downloading(0, total)) }
                    is DownloadEvent.Progress -> setState(id, ModelState.Downloading(e.downloadedBytes, total))
                    DownloadEvent.Completed -> ready(id)
                    is DownloadEvent.Failed -> setState(id, ModelState.Failed(e.error.message ?: "Download failed"))
                }
            }
        }
    }

    /** Stops a download. What arrived so far is kept, so the next download picks up from there. */
    fun cancelDownload(id: ModelId) {
        downloads.remove(id)?.cancel()
        refresh(id)
    }

    /** Frees the model's memory and deletes its downloaded files. */
    fun delete(id: ModelId) {
        downloads.remove(id)?.cancel()
        scope.launch {
            val b = backend(id)
            engineLock.withLock {
                b.unload()
                if (loadedHeavy === b) loadedHeavy = null
            }
            b.delete()
            resolve(id)
        }
    }

    /** Loads the model ahead of the first message, in the background. */
    fun warm(id: ModelId) {
        if (_states.value[id] != ModelState.Ready) return
        scope.launch { runCatching { use(id) { warmup(id, it) } } }
    }

    /**
     * Runs [block] with the model loaded and no other heavy engine in memory. Requests run one at a time,
     * so a model is never unloaded while it is answering.
     */
    suspend fun <T> use(id: ModelId, block: suspend (ModelBackend) -> T): T = engineLock.withLock {
        val b = backend(id)
        if (b.heavy && loadedHeavy !== b) {
            loadedHeavy?.let { old -> old.unload(); ModelId.entries.filter { backend(it) === old }.forEach { m -> setInfo(m) { i -> i.copy(loaded = false) } } }
            loadedHeavy = b
        }
        try {
            block(b)
        } finally {
            refreshDetails(id, b)
        }
    }

    private suspend fun warmup(id: ModelId, b: ModelBackend) {
        setInfo(id) { it.copy(loading = true) }
        val mark = TimeSource.Monotonic.markNow()
        try {
            b.warmup()
            setInfo(id) { it.copy(lastWarmupMs = mark.elapsedNow().inWholeMilliseconds) }
        } finally {
            setInfo(id) { it.copy(loading = false) }
        }
    }

    private suspend fun refreshDetails(id: ModelId, b: ModelBackend) {
        val details = runCatching { b.info() }.getOrNull()
        setInfo(id) {
            it.copy(
                baseModelName = details?.baseModelName,
                cachingAvailable = details?.cachingAvailable ?: false,
                aicoreVersion = details?.aicoreVersion,
                accelerator = details?.accelerator,
                loadMs = details?.loadMs,
                speculativeDecoding = details?.speculativeDecoding,
                fileBytes = details?.fileBytes,
                loaded = b.heavy && loadedHeavy === b && details?.accelerator != null,
            )
        }
    }

    private suspend fun ready(id: ModelId) {
        setState(id, ModelState.Ready)
        val b = backend(id)
        val tokenLimit = runCatching { b.tokenLimit() }.getOrNull()
        val inputCap = runCatching { b.inputTokenCap() }.getOrNull()
        val system = runCatching { b.supportsSystemPrompt() }.getOrDefault(false)
        setInfo(id) { it.copy(tokenLimit = tokenLimit, inputTokenCap = inputCap, systemPromptAvailable = system, nativeChat = b.nativeChat) }
        refreshDetails(id, b)
        // Nano runs inside AICore, so warming it costs this app nothing. Heavy engines load when a chat opens.
        if (!b.heavy) warm(id)
    }
}
