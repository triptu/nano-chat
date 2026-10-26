package ai.tushar.nanochat.devtools

import ai.tushar.nanochat.AppGraph
import ai.tushar.nanochat.data.Generation
import ai.tushar.nanochat.data.GenerationTotals
import ai.tushar.nanochat.model.BackendKind
import ai.tushar.nanochat.model.FakeScenario
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
    val model: ModelState = ModelState.Checking,
    val info: ModelInfo = ModelInfo(BackendKind.Real, BackendChoice.Auto, null),
    val fakeScenario: FakeScenario = FakeScenario.Normal,
    val defaults: GenerationParams = GenerationParams.Default,
    val totals: GenerationTotals? = null,
    val messageCount: Int = 0,
    val log: List<Pair<Generation, String>> = emptyList(),
)

class StatsViewModel(private val graph: AppGraph) : ViewModel() {
    private val names = graph.dao.agentPreviews().map { list -> list.associate { it.agent.id to it.agent.name } }

    val state: StateFlow<StatsState> = combine(
        combine(graph.model.state, graph.model.info, graph.settings.fakeScenario, ::Triple),
        graph.settings.defaultParams,
        graph.dao.generationTotals(),
        graph.dao.messageCount(),
        combine(graph.dao.recentGenerations(LogSize), names) { gens, n -> gens.map { it to (n[it.agentId] ?: "?") } },
    ) { (model, info, scenario), defaults, totals, count, log ->
        StatsState(model, info, scenario, defaults, totals, count, log)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsState())

    fun setDefaults(p: GenerationParams) = viewModelScope.launch { graph.settings.setDefaultParams(p) }
    fun setBackend(c: BackendChoice) = viewModelScope.launch { graph.settings.setBackendChoice(c) }
    fun setScenario(s: FakeScenario) = viewModelScope.launch { graph.settings.setFakeScenario(s) }
    fun download() = graph.model.download()
    fun refresh() = graph.model.refresh()
    fun warmup() = viewModelScope.launch { runCatching { graph.model.warmup() } }
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
            item { ModelCard(s, vm) }
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
private fun ModelCard(s: StatsState, vm: StatsViewModel) {
    val info = s.info
    Card("Model") {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            BackendChoice.entries.forEachIndexed { i, c ->
                SegmentedButton(
                    selected = info.choice == c,
                    onClick = { vm.setBackend(c) },
                    shape = SegmentedButtonDefaults.itemShape(i, BackendChoice.entries.size),
                ) { Text(c.label) }
            }
        }
        Fact("Running", info.backend.label)
        Fact("Status", statusText(s.model))
        Fact("Real model check", info.realAvailability?.name ?: "no answer from AICore")
        info.baseModelName?.let { Fact("Base model", it) }
        info.tokenLimit?.let { Fact("Token limit", "$it (input + output)") }
        Fact("System instructions", if (info.systemPromptAvailable) "supported" else "not supported")
        Fact("Prefix caching", if (info.cachingAvailable) "supported" else "not supported")
        Fact("AICore", info.aicoreVersion ?: "not installed")
        info.lastWarmupMs?.let { Fact("Last warm-up", "$it ms") }
        if (info.backend == BackendKind.Fake) {
            Text("Fake model behaviour", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
            FakeScenario.entries.forEach { sc ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .selectable(selected = s.fakeScenario == sc, role = Role.RadioButton) { vm.setScenario(sc) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    androidx.compose.material3.RadioButton(selected = s.fakeScenario == sc, onClick = null)
                    Text(sc.label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = vm::download, enabled = s.model == ModelState.Downloadable || s.model is ModelState.Failed) { Text("Download") }
            FilledTonalButton(onClick = vm::warmup, enabled = s.model == ModelState.Ready) { Text("Warm up") }
            OutlinedButton(onClick = vm::refresh) { Text("Check again") }
        }
    }
}

private fun statusText(m: ModelState): String = when (m) {
    ModelState.Checking -> "checking…"
    ModelState.Ready -> "ready"
    ModelState.Downloadable -> "needs download"
    is ModelState.Downloading -> "downloading " + (m.totalBytes?.let { "${(m.downloadedBytes * 100 / it)}%" } ?: "${m.downloadedBytes / 1_000_000} MB")
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
            val tps = if (g.ttftMs != null && g.outputTokens > 1 && g.totalMs > g.ttftMs) (g.outputTokens - 1) * 1000 / (g.totalMs - g.ttftMs) else null
            Text(
                listOfNotNull(
                    g.ttftMs?.let { "${it} ms first token" },
                    tps?.let { "$it tok/s" },
                    "${g.inputTokens} in / ${g.outputTokens} out",
                    if (g.backend == BackendKind.Fake.name) "fake" else null,
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

