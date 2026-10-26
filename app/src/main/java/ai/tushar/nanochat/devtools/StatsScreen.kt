package ai.tushar.nanochat.devtools

import ai.tushar.nanochat.AppGraph
import ai.tushar.nanochat.data.Generation
import ai.tushar.nanochat.data.GenerationTotals
import ai.tushar.nanochat.model.BackendKind
import ai.tushar.nanochat.model.FakeScenario
import ai.tushar.nanochat.gate.bytesText
import ai.tushar.nanochat.model.ModelId
import ai.tushar.nanochat.model.ModelInfo
import ai.tushar.nanochat.model.ModelState
import ai.tushar.nanochat.settings.BackendChoice
import ai.tushar.nanochat.settings.GenerationParams
import ai.tushar.nanochat.ui.Ico
import ai.tushar.nanochat.ui.Icons
import ai.tushar.nanochat.ui.relativeTime
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

private const val LogSize = 30

data class StatsState(
    val states: Map<ModelId, ModelState> = ModelId.entries.associateWith { ModelState.Checking },
    val infos: Map<ModelId, ModelInfo> = ModelId.entries.associateWith { ModelInfo(it, BackendKind.Real, BackendChoice.Auto, null) },
    val defaultModel: ModelId = ModelId.Default,
    val fakeScenario: FakeScenario = FakeScenario.Normal,
    val defaults: GenerationParams = GenerationParams.Default,
    val totals: GenerationTotals? = null,
    val messageCount: Int = 0,
    val log: List<Pair<Generation, String>> = emptyList(),
) {
    /** Real or fake applies to every model at once, so any model's info says which. */
    val choice: BackendChoice get() = infos.values.first().choice
    val anyFake: Boolean get() = infos.values.any { it.backend == BackendKind.Fake }
}

private data class ModelsPart(
    val states: Map<ModelId, ModelState>,
    val infos: Map<ModelId, ModelInfo>,
    val defaultModel: ModelId,
    val scenario: FakeScenario,
)

class StatsViewModel(private val graph: AppGraph) : ViewModel() {
    private val names = graph.dao.agentPreviews().map { list -> list.associate { it.agent.id to it.agent.name } }

    val state: StateFlow<StatsState> = combine(
        combine(graph.models.states, graph.models.infos, graph.settings.defaultModel, graph.settings.fakeScenario, ::ModelsPart),
        graph.settings.defaultParams,
        graph.dao.generationTotals(),
        graph.dao.messageCount(),
        combine(graph.dao.recentGenerations(LogSize), names) { gens, n -> gens.map { it to (n[it.agentId] ?: "?") } },
    ) { m, defaults, totals, count, log ->
        StatsState(m.states, m.infos, m.defaultModel, m.scenario, defaults, totals, count, log)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsState())

    fun setDefaults(p: GenerationParams) = viewModelScope.launch { graph.settings.setDefaultParams(p) }
    fun setDefaultModel(m: ModelId) = viewModelScope.launch { graph.settings.setDefaultModel(m) }
    fun setBackend(c: BackendChoice) = viewModelScope.launch { graph.settings.setBackendChoice(c) }
    fun setScenario(s: FakeScenario) = viewModelScope.launch { graph.settings.setFakeScenario(s) }
    fun download(id: ModelId) = graph.models.download(id)
    fun cancel(id: ModelId) = graph.models.cancelDownload(id)
    fun remove(id: ModelId) = graph.models.delete(id)
    fun refresh(id: ModelId) = graph.models.refresh(id)
    fun load(id: ModelId) = graph.models.warm(id)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(vm: StatsViewModel, onBack: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Ico(Icons.Back, "Back") } },
                title = { Text("Stats & settings for nerds") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { DefaultModelCard(s, vm) }
            item { RealOrFakeCard(s, vm) }
            items(ModelId.entries, key = { it.name }) { id -> ModelCard(id, s.states.getValue(id), s.infos.getValue(id), vm) }
            item {
                Card("Default model settings") {
                    Text(
                        "Every chat uses these unless it has its own settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ParamSliders(s.defaults, vm::setDefaults)
                }
            }
            item { TotalsCard(s) }
            item {
                Text("Recent replies", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
            }
            if (s.log.isEmpty()) {
                item { Text("No replies yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp)) }
            }
            items(s.log, key = { it.first.id }) { (g, name) -> LogRow(g, name) }
            item { Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars)) }
        }
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DefaultModelCard(s: StatsState, vm: StatsViewModel) {
    Card("Default model") {
        Text(
            "Companions use this unless they pick their own model in their edit sheet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ModelId.entries.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = s.defaultModel == m,
                    onClick = { vm.setDefaultModel(m) },
                    shape = SegmentedButtonDefaults.itemShape(i, ModelId.entries.size),
                ) { Text(m.label, maxLines = 1) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RealOrFakeCard(s: StatsState, vm: StatsViewModel) {
    Card("Real or fake models") {
        Text(
            "Applies to every model. Auto uses the real ones, and in debug builds falls back to the fake one where a real model can't run.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            BackendChoice.entries.forEachIndexed { i, c ->
                SegmentedButton(
                    selected = s.choice == c,
                    onClick = { vm.setBackend(c) },
                    shape = SegmentedButtonDefaults.itemShape(i, BackendChoice.entries.size),
                ) { Text(c.label) }
            }
        }
        if (s.anyFake) {
            Text("Fake model behaviour", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
            Column(Modifier.selectableGroup()) {
                FakeScenario.entries.forEach { sc ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp)
                            .selectable(selected = s.fakeScenario == sc, role = Role.RadioButton) { vm.setScenario(sc) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = s.fakeScenario == sc, onClick = null)
                        Text(sc.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelCard(id: ModelId, state: ModelState, info: ModelInfo, vm: StatsViewModel) {
    var confirmRemove by remember { mutableStateOf(false) }
    val fake = info.backend == BackendKind.Fake
    val systemManaged = id == ModelId.Nano && !fake
    Card(id.label + if (fake) " (fake)" else "") {
        Fact("Status", statusText(state, info))
        Fact(
            "Size",
            when {
                systemManaged -> "managed by Android (AICore)"
                else -> listOfNotNull(
                    info.downloadBytes?.let(::bytesText),
                    when {
                        state is ModelState.Downloading -> "${bytesText(state.downloadedBytes)} downloaded"
                        state == ModelState.Ready -> "downloaded"
                        else -> "not downloaded"
                    },
                ).joinToString(" · ")
            },
        )
        if (!systemManaged) {
            Fact("Backend", info.accelerator ?: if (info.loading) "loading…" else "not loaded")
            info.loadMs?.let { Fact("Load time", String.format(Locale.US, "%.1f s", it / 1000f)) }
            info.speculativeDecoding?.let { Fact("Speculative decoding", if (it) "on" else "off") }
        }
        info.tokenLimit?.let { Fact("Token limit", "$it (input + output)") }
        info.inputTokenCap?.let { Fact("Input cap", "$it tokens") }
        // Known once the model is ready.
        if (state == ModelState.Ready) Fact(
            "System instructions",
            when {
                info.nativeChat -> "native, with chat turns"
                info.systemPromptAvailable -> "native, in one transcript"
                else -> "inline, at the start of the prompt"
            },
        )
        Fact("Media", mediaText(info.media))
        if (id == ModelId.Nano) {
            Fact("Real model check", info.realAvailability?.name ?: "no answer from AICore")
            info.baseModelName?.let { Fact("Base model", it) }
            Fact("Prefix caching", if (info.cachingAvailable) "supported" else "not supported")
            Fact("AICore", info.aicoreVersion ?: "not installed")
        } else {
            info.baseModelName?.let { Fact("File", it) }
        }
        info.lastWarmupMs?.let { Fact("Last warm-up", "$it ms") }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                ModelState.Downloadable, is ModelState.Failed -> FilledTonalButton(onClick = { vm.download(id) }) { Text("Download") }
                is ModelState.Downloading -> if (!systemManaged) FilledTonalButton(onClick = { vm.cancel(id) }) { Text("Cancel") }
                ModelState.Ready -> FilledTonalButton(onClick = { vm.load(id) }, enabled = !info.loading && !info.loaded) {
                    Text(if (info.loading) "Loading…" else if (systemManaged) "Warm up" else "Load now")
                }
                else -> Unit
            }
            if (!systemManaged && state == ModelState.Ready) OutlinedButton(onClick = { confirmRemove = true }) { Text("Remove") }
            OutlinedButton(onClick = { vm.refresh(id) }) { Text("Check again") }
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove ${id.label}?") },
            text = {
                Text(
                    "This deletes the downloaded model" + (info.fileBytes ?: info.downloadBytes)?.let { " (${bytesText(it)})" }.orEmpty() +
                        " and frees its memory. Chats that use it will ask to download it again.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { confirmRemove = false; vm.remove(id) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

private fun statusText(m: ModelState, info: ModelInfo): String = when (m) {
    ModelState.Checking -> "checking…"
    ModelState.Ready -> when {
        info.loading -> "ready, loading into memory"
        info.loaded -> "ready, loaded"
        else -> "ready"
    }
    ModelState.Downloadable -> "needs download"
    is ModelState.Downloading -> "downloading " + percent(m)
    ModelState.Unsupported -> "unsupported"
    is ModelState.Failed -> "failed: ${m.message}"
}

@Composable
private fun TotalsCard(s: StatsState) {
    val t = s.totals
    Card("Totals") {
        Row(Modifier.fillMaxWidth()) {
            Number("Avg first token", t?.avgTtftMs?.let { String.format(Locale.US, "%.2f s", it / 1000) } ?: "–", Modifier.weight(1f))
            Number("Avg speed", t?.avgTokensPerSecond?.let { "${it.roundToInt()} tok/s" } ?: "–", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth()) {
            Number("Messages", s.messageCount.toString(), Modifier.weight(1f))
            Number("Tokens in / out", "${t?.totalInputTokens ?: 0} / ${t?.totalOutputTokens ?: 0}", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth()) {
            Number("Replies", (t?.count ?: 0).toString(), Modifier.weight(1f))
            Number("Errors", (t?.errors ?: 0).toString(), Modifier.weight(1f))
        }
    }
}

@Composable
private fun Number(label: String, value: String, modifier: Modifier) {
    Column(modifier.padding(vertical = 4.dp)) {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LogRow(g: Generation, agentName: String) {
    var open by rememberSaveable(g.id) { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        onClick = { open = !open },
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(agentName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(relativeTime(g.startedAt), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val tps = g.decodeTokensPerSecond?.roundToInt()
                ?: if (g.ttftMs != null && g.outputTokens > 1 && g.totalMs > g.ttftMs) ((g.outputTokens - 1) * 1000 / (g.totalMs - g.ttftMs)).toInt() else null
            Text(
                listOfNotNull(
                    ModelId.parse(g.model)?.label,
                    g.accelerator,
                    if (g.backend == BackendKind.Fake.name) "fake" else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                listOfNotNull(
                    g.ttftMs?.let { "${it} ms first token" },
                    g.prefillTokensPerSecond?.let { "prefill ${it.roundToInt()} tok/s" },
                    tps?.let { if (g.prefillTokensPerSecond != null) "decode $it tok/s" else "$it tok/s" },
                    "${g.inputTokens} in / ${g.outputTokens} out",
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                g.error ?: (g.finishReason ?: "no finish reason"),
                style = MaterialTheme.typography.bodySmall,
                color = if (g.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (open) 6 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (open) {
                Text(
                    g.prompt,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

