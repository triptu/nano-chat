package ai.tushar.nanochat.model

import ai.tushar.nanochat.settings.BackendChoice
import ai.tushar.nanochat.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource

sealed interface ModelState {
    data object Checking : ModelState
    /** The phone can't run Gemini Nano (or AICore hasn't fetched its config yet). */
    data object Unsupported : ModelState
    data object Downloadable : ModelState
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long?) : ModelState
    data object Ready : ModelState
    data class Failed(val message: String) : ModelState
}

/** Facts about the active model, for the dev tools. */
data class ModelInfo(
    val backend: BackendKind,
    val choice: BackendChoice,
    val realAvailability: Availability?,
    val baseModelName: String? = null,
    val tokenLimit: Int? = null,
    val systemPromptAvailable: Boolean = false,
    val cachingAvailable: Boolean = false,
    val aicoreVersion: String? = null,
    val lastWarmupMs: Long? = null,
)

/**
 * Picks which backend runs and tracks whether it is ready. "Auto" means the real model, except that
 * debug builds fall back to the fake one when the real model is unavailable (as on an emulator).
 */
class NanoModel(
    private val real: ModelBackend,
    private val fake: FakeBackend,
    private val allowAutoFake: Boolean,
    private val settings: Settings,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<ModelState>(ModelState.Checking)
    val state: StateFlow<ModelState> = _state.asStateFlow()

    private val _info = MutableStateFlow(ModelInfo(BackendKind.Real, BackendChoice.Auto, null))
    val info: StateFlow<ModelInfo> = _info.asStateFlow()

    @Volatile var backend: ModelBackend = real
        private set

    private val resolveLock = Mutex()
    private var downloadJob: Job? = null
    private var choice = BackendChoice.Auto

    fun start() {
        scope.launch {
            combine(settings.backendChoice, settings.fakeScenario) { c, s -> c to s }.collectLatest { (c, s) ->
                choice = c
                fake.scenario = s
                resolve()
            }
        }
    }

    fun refresh() {
        scope.launch { resolve() }
    }

    private suspend fun resolve() = resolveLock.withLock {
        val realAvailability = runCatching { real.availability() }.getOrNull()
        val picked = when (choice) {
            BackendChoice.Real -> real
            BackendChoice.Fake -> fake
            BackendChoice.Auto -> if (allowAutoFake && (realAvailability == null || realAvailability == Availability.Unavailable)) fake else real
        }
        if (picked !== backend) {
            downloadJob?.cancel()
            downloadJob = null
        }
        backend = picked
        _info.value = ModelInfo(picked.kind, choice, realAvailability)
        val availability = if (picked === real) realAvailability else runCatching { picked.availability() }.getOrNull()
        when (availability) {
            Availability.Available -> ready()
            Availability.Downloadable -> if (downloadJob?.isActive != true) _state.value = ModelState.Downloadable
            Availability.Downloading -> download()
            Availability.Unavailable -> _state.value = ModelState.Unsupported
            null -> _state.value = ModelState.Failed("Couldn't reach Google AICore")
        }
    }

    fun download() {
        if (downloadJob?.isActive == true) return
        val source = backend
        _state.value = ModelState.Downloading(0, null)
        downloadJob = scope.launch {
            var total: Long? = null
            source.download().collect { e ->
                when (e) {
                    is DownloadEvent.Started -> { total = e.totalBytes; _state.value = ModelState.Downloading(0, total) }
                    is DownloadEvent.Progress -> _state.value = ModelState.Downloading(e.downloadedBytes, total)
                    DownloadEvent.Completed -> ready()
                    is DownloadEvent.Failed -> _state.value = ModelState.Failed(e.error.message ?: "Download failed")
                }
            }
        }
    }

    /** Loads the model into memory ahead of the first message. Returns how long it took. */
    suspend fun warmup(): Long {
        val mark = TimeSource.Monotonic.markNow()
        backend.warmup()
        return mark.elapsedNow().inWholeMilliseconds.also { ms -> _info.value = _info.value.copy(lastWarmupMs = ms) }
    }

    suspend fun tokenLimit(): Int = _info.value.tokenLimit ?: backend.tokenLimit()

    private suspend fun ready() {
        _state.value = ModelState.Ready
        val b = backend
        val details = runCatching { b.info() }.getOrNull()
        _info.value = _info.value.copy(
            baseModelName = details?.baseModelName,
            tokenLimit = runCatching { b.tokenLimit() }.getOrNull(),
            systemPromptAvailable = b.supportsSystemPrompt(),
            cachingAvailable = details?.cachingAvailable ?: false,
            aicoreVersion = details?.aicoreVersion,
        )
        scope.launch { runCatching { warmup() } }
    }
}
