package ai.tushar.nanochat.profile

import ai.tushar.nanochat.AppGraph
import ai.tushar.nanochat.home.ProfileBadge
import ai.tushar.nanochat.ui.Ico
import ai.tushar.nanochat.ui.Icons
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProfileViewModel(private val graph: AppGraph) : ViewModel() {
    var name by mutableStateOf("")
        private set

    val devTools = graph.settings.devToolsEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    init {
        viewModelScope.launch { name = graph.settings.displayName.first() }
    }

    fun rename(value: String) {
        name = value.take(30)
        viewModelScope.launch { graph.settings.setDisplayName(name.trim()) }
    }

    fun setDevTools(on: Boolean) = viewModelScope.launch { graph.settings.setDevToolsEnabled(on) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(vm: ProfileViewModel, onBack: () -> Unit, onOpenStats: () -> Unit) {
    val devTools by vm.devTools.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Ico(Icons.Back, "Back") } },
                title = { Text("Profile") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ProfileBadge(vm.name, size = 88.dp)
            OutlinedTextField(
                value = vm.name,
                onValueChange = vm::rename,
                label = { Text("Your name") },
                supportingText = { Text("Your friends use it when they talk to you.") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            )
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                Column {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(value = devTools, role = Role.Switch, onValueChange = { vm.setDevTools(it) })
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Developer tools", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Shows speed under each reply, the exact prompt behind a chat, model settings and a stats page.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(checked = devTools, onCheckedChange = null)
                    }
                    if (devTools) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(horizontal = 16.dp))
                        Row(
                            Modifier.fillMaxWidth().clickable(onClick = onOpenStats).padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Ico(Icons.Nerd, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(14.dp))
                            Text("Stats & settings for nerds", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Ico(Icons.Chevron, null, size = 18.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Text(
                "Chats run on Gemini Nano, right on this phone. Nothing you write leaves it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
    }
}
