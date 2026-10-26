package ai.tushar.nanochat.home

import ai.tushar.nanochat.AppGraph
import ai.tushar.nanochat.data.Agent
import ai.tushar.nanochat.data.AgentPreview
import ai.tushar.nanochat.data.Role
import ai.tushar.nanochat.ui.Ico
import ai.tushar.nanochat.ui.Icons
import ai.tushar.nanochat.ui.mascot.BlobAvatar
import ai.tushar.nanochat.ui.relativeTime
import ai.tushar.nanochat.ui.theme.tone
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class AgentRow(
    val agent: Agent,
    val preview: String?,
    val previewIsMine: Boolean,
    val typing: Boolean,
    val lastAt: Long?,
)

data class HomeState(val rows: List<AgentRow> = emptyList(), val displayName: String = "", val loaded: Boolean = false)

class HomeViewModel(graph: AppGraph) : ViewModel() {
    val state: StateFlow<HomeState> = combine(
        graph.dao.agentPreviews(),
        graph.engine.pending,
        graph.settings.displayName,
    ) { previews: List<AgentPreview>, pending, name ->
        HomeState(
            rows = previews.map { p ->
                AgentRow(
                    agent = p.agent,
                    preview = p.lastText,
                    previewIsMine = p.lastRole == Role.User,
                    typing = p.agent.id in pending,
                    lastAt = p.lastAt,
                )
            },
            displayName = name,
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    selectedAgentId: Long?,
    onOpenAgent: (Long) -> Unit,
    onNewAgent: () -> Unit,
    onOpenProfile: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Nano Chat", style = MaterialTheme.typography.headlineMedium) },
                actions = {
                    IconButton(onClick = onOpenProfile) { ProfileBadge(state.displayName) }
                },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewAgent,
                icon = { Ico(Icons.Add, null, size = 20.dp) },
                text = { Text("New friend") },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 96.dp),
        ) {
            items(state.rows, key = { it.agent.id }) { row ->
                AgentRowItem(row, selected = row.agent.id == selectedAgentId, onClick = { onOpenAgent(row.agent.id) })
            }
        }
    }
}

@Composable
fun ProfileBadge(name: String, size: androidx.compose.ui.unit.Dp = 34.dp) {
    Box(
        Modifier
            .size(size)
            .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val initial = name.trim().firstOrNull()?.uppercaseChar()
        if (initial != null) {
            Text(
                initial.toString(),
                style = if (size > 48.dp) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        } else {
            Ico(ai.tushar.nanochat.R.drawable.ic_user_circle, "Profile", size = size * 0.7f, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
private fun AgentRowItem(row: AgentRow, selected: Boolean, onClick: () -> Unit) {
    val dark = isSystemInDarkTheme()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else androidx.compose.ui.graphics.Color.Transparent, MaterialTheme.shapes.large)
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BlobAvatar(row.agent.color.tone(dark), 54.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.agent.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                row.lastAt?.let {
                    Text(relativeTime(it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val previewStyle = MaterialTheme.typography.bodyMedium
            when {
                row.typing -> Text("typing…", style = previewStyle, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                row.preview != null -> Text(
                    (if (row.previewIsMine) "You: " else "") + row.preview.replace('\n', ' '),
                    style = previewStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                else -> Text("Say hi", style = previewStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}


