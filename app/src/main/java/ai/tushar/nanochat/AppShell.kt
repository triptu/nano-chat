package ai.tushar.nanochat

import ai.tushar.nanochat.agents.AgentDraft
import ai.tushar.nanochat.agents.AgentEditorSheet
import ai.tushar.nanochat.agents.AgentsViewModel
import ai.tushar.nanochat.chat.AgentActions
import ai.tushar.nanochat.chat.ChatScreen
import ai.tushar.nanochat.chat.ChatViewModel
import ai.tushar.nanochat.data.Agent
import ai.tushar.nanochat.devtools.LocalDevTools
import ai.tushar.nanochat.devtools.StatsScreen
import ai.tushar.nanochat.devtools.StatsViewModel
import ai.tushar.nanochat.gate.ModelGate
import ai.tushar.nanochat.home.HomeScreen
import ai.tushar.nanochat.home.HomeViewModel
import ai.tushar.nanochat.model.ModelState
import ai.tushar.nanochat.profile.ProfileScreen
import ai.tushar.nanochat.profile.ProfileViewModel
import ai.tushar.nanochat.settings.BackendChoice
import ai.tushar.nanochat.settings.GenerationParams
import ai.tushar.nanochat.ui.graphViewModel
import ai.tushar.nanochat.ui.mascot.Blob
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import kotlinx.coroutines.launch

/** What the detail pane shows. Encoded as a string so the navigator can save it. */
private sealed interface Detail {
    data class Chat(val agentId: Long) : Detail
    data object Profile : Detail
    data object Stats : Detail

    val key: String get() = when (this) {
        is Chat -> "chat:$agentId"
        Profile -> "profile"
        Stats -> "stats"
    }

    companion object {
        fun parse(key: String?): Detail? = when {
            key == null -> null
            key.startsWith("chat:") -> key.removePrefix("chat:").toLongOrNull()?.let(::Chat)
            key == "profile" -> Profile
            key == "stats" -> Stats
            else -> null
        }
    }
}

/** Editor sheet target: a new agent, or an existing one. */
private sealed interface Editing {
    data object New : Editing
    data class Existing(val agent: Agent) : Editing
}

@Composable
fun AppRoot() {
    val graph = LocalContext.current.graph
    val devTools by graph.settings.devToolsEnabled.collectAsStateWithLifecycle(initialValue = true)
    val model by graph.model.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    CompositionLocalProvider(LocalDevTools provides devTools) {
        AnimatedContent(
            targetState = model == ModelState.Ready,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
            label = "gate",
        ) { ready ->
            if (ready) {
                AppShell()
            } else {
                ModelGate(
                    state = model,
                    onDownload = graph.model::download,
                    onRetry = graph.model::refresh,
                    onUseFake = { scope.launch { graph.settings.setBackendChoice(BackendChoice.Fake) } },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun AppShell() {
    val adaptive = currentWindowAdaptiveInfo()
    val twoPaneCapable = adaptive.windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    val directive = calculatePaneScaffoldDirective(adaptive).let {
        it.copy(maxHorizontalPartitions = if (twoPaneCapable) 2 else 1, horizontalPartitionSpacerSize = 0.dp)
    }
    val navigator = rememberListDetailPaneScaffoldNavigator<String>(scaffoldDirective = directive)
    val scope = rememberCoroutineScope()
    val agentsVm = graphViewModel { AgentsViewModel(it) }
    val graph = LocalContext.current.graph
    val defaults by graph.settings.defaultParams.collectAsStateWithLifecycle(initialValue = GenerationParams.Default)
    var editing by remember { mutableStateOf<Editing?>(null) }

    val detail = Detail.parse(navigator.currentDestination?.contentKey)
    val detailVisible = navigator.scaffoldValue[ListDetailPaneScaffoldRole.Detail] == PaneAdaptedValue.Expanded
    val singlePane = navigator.scaffoldValue[ListDetailPaneScaffoldRole.List] != PaneAdaptedValue.Expanded || !detailVisible

    fun show(d: Detail) = scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, d.key) }
    fun back() = scope.launch { navigator.navigateBack() }

    NavigableListDetailPaneScaffold(
        navigator = navigator,
        listPane = {
            AnimatedPane(Modifier.preferredWidth(380.dp)) {
                val homeVm = graphViewModel { HomeViewModel(it) }
                HomeScreen(
                    vm = homeVm,
                    selectedAgentId = (detail as? Detail.Chat)?.agentId.takeIf { !singlePane },
                    onOpenAgent = { show(Detail.Chat(it)) },
                    onNewAgent = { editing = Editing.New },
                    onOpenProfile = { show(Detail.Profile) },
                )
            }
        },
        detailPane = {
            AnimatedPane {
                when (val d = detail) {
                    is Detail.Chat -> {
                        val vm = graphViewModel(key = d.key) { ChatViewModel(it, d.agentId) }
                        ChatScreen(
                            vm = vm,
                            showBack = singlePane,
                            onBack = { back() },
                            actions = AgentActions(
                                onEdit = { editing = Editing.Existing(it) },
                                onClear = { agentsVm.clearChat(it.id) },
                                onDelete = { agentsVm.delete(it.id); back() },
                            ),
                        )
                    }
                    Detail.Profile -> ProfileScreen(graphViewModel { ProfileViewModel(it) }, onBack = { back() }, onOpenStats = { show(Detail.Stats) })
                    Detail.Stats -> StatsScreen(graphViewModel { StatsViewModel(it) }, onBack = { back() })
                    null -> NothingSelected()
                }
            }
        },
    )

    editing?.let { e ->
        AgentEditorSheet(
            initial = (e as? Editing.Existing)?.agent?.let(AgentDraft::of),
            defaults = defaults,
            onDismiss = { editing = null },
            onSave = { draft ->
                when (e) {
                    Editing.New -> agentsVm.create(draft) { id -> show(Detail.Chat(id)) }
                    is Editing.Existing -> agentsVm.update(e.agent, draft)
                }
            },
        )
    }
}

@Composable
private fun NothingSelected() {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Blob(Color(0xFFFF8A6B), 96.dp)
            Spacer(Modifier.height(16.dp))
            Text("Pick someone to chat with", style = MaterialTheme.typography.titleLarge)
        }
    }
}
