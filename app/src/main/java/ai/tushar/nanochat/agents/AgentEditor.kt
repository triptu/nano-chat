package ai.tushar.nanochat.agents

import ai.tushar.nanochat.AppGraph
import ai.tushar.nanochat.data.Agent
import ai.tushar.nanochat.data.AgentColor
import ai.tushar.nanochat.devtools.AgentParamsSection
import ai.tushar.nanochat.devtools.DevOnly
import ai.tushar.nanochat.devtools.modelStatusLine
import ai.tushar.nanochat.model.ModelId
import ai.tushar.nanochat.model.ModelInfo
import ai.tushar.nanochat.model.ModelState
import ai.tushar.nanochat.ui.theme.LocalChatColors
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.RadioButton
import androidx.compose.ui.draw.clip
import ai.tushar.nanochat.settings.GenerationParams
import ai.tushar.nanochat.ui.Ico
import ai.tushar.nanochat.ui.Icons
import ai.tushar.nanochat.ui.mascot.Blob
import ai.tushar.nanochat.ui.theme.tone
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

/** What the editor sheet is filling in. Null params mean "use the global default". */
data class AgentDraft(
    val name: String = "",
    val color: AgentColor = AgentColor.Rose,
    val personality: String = "",
    val temperature: Float? = null,
    val topK: Int? = null,
    val maxOutputTokens: Int? = null,
    /** Null means "use the default model". */
    val model: ModelId? = null,
) {
    val canSave: Boolean get() = name.isNotBlank() && personality.isNotBlank()

    companion object {
        fun of(a: Agent) = AgentDraft(a.name, a.color, a.personality, a.temperature, a.topK, a.maxOutputTokens, ModelId.parse(a.model))
    }
}

/** A model the editor can offer, with how ready it is (shown only with dev tools on). */
data class ModelChoice(val id: ModelId, val state: ModelState, val info: ModelInfo)

class AgentsViewModel(private val graph: AppGraph) : ViewModel() {
    fun create(d: AgentDraft, then: (Long) -> Unit) = viewModelScope.launch {
        val id = graph.dao.insertAgent(
            Agent(
                name = d.name.trim(), color = d.color, personality = d.personality.trim(),
                temperature = d.temperature, topK = d.topK, maxOutputTokens = d.maxOutputTokens,
                createdAt = System.currentTimeMillis(), model = d.model?.name,
            ),
        )
        then(id)
    }

    fun update(agent: Agent, d: AgentDraft) = viewModelScope.launch {
        graph.dao.updateAgent(
            agent.copy(
                name = d.name.trim(), color = d.color, personality = d.personality.trim(),
                temperature = d.temperature, topK = d.topK, maxOutputTokens = d.maxOutputTokens,
                model = d.model?.name,
            ),
        )
    }

    fun delete(agentId: Long) = viewModelScope.launch {
        graph.engine.stop(agentId)
        graph.drafts.take(agentId).forEach(graph.attachments::discard)
        graph.dao.deleteAgent(agentId)
        graph.cleanUpAttachments()
    }

    fun clearChat(agentId: Long) = viewModelScope.launch {
        graph.engine.stop(agentId)
        graph.dao.clearMessages(agentId)
        graph.cleanUpAttachments()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentEditorSheet(
    initial: AgentDraft?,
    defaults: GenerationParams,
    defaultModel: ModelId,
    models: List<ModelChoice>,
    onDismiss: () -> Unit,
    onSave: (AgentDraft) -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(initial ?: AgentDraft()) }
    val dark = isSystemInDarkTheme()
    val tint by animateColorAsState(draft.color.tone(dark), label = "tint")
    val isNew = initial == null

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.size(72.dp).background(tint.copy(alpha = 0.22f), CircleShape), contentAlignment = Alignment.Center) {
                    Blob(tint, 62.dp)
                }
                Column {
                    Text(if (isNew) "New friend" else "Edit ${initial?.name}", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Who are they, and how do they talk?",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OutlinedTextField(
                value = draft.name,
                onValueChange = { draft = draft.copy(name = it.take(24)) },
                label = { Text("Name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                AgentColor.entries.forEach { c ->
                    val selected = c == draft.color
                    Box(
                        Modifier
                            .size(40.dp)
                            .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = if (selected) 0.85f else 0f), CircleShape)
                            .padding(5.dp)
                            .background(c.tone(dark), CircleShape)
                            .clickable(role = Role.RadioButton) { draft = draft.copy(color = c) }
                            .semantics { contentDescription = c.name; this.selected = selected },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) Ico(Icons.Check, null, size = 18.dp, tint = Color(0xFF2E140A))
                    }
                }
            }
            OutlinedTextField(
                value = draft.personality,
                onValueChange = { draft = draft.copy(personality = it.take(800)) },
                label = { Text("Personality") },
                placeholder = { Text("A patient tutor who explains things with simple examples.") },
                supportingText = { Text("${draft.name.ifBlank { "They" }.trim()} will follow this in every reply.") },
                minLines = 3,
                maxLines = 6,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                shape = MaterialTheme.shapes.medium,
            )
            ModelPicker(draft.model, defaultModel, models) { draft = draft.copy(model = it) }
            DevOnly {
                AgentParamsSection(
                    temperature = draft.temperature,
                    topK = draft.topK,
                    maxOutputTokens = draft.maxOutputTokens,
                    defaults = defaults,
                    onChange = { t, k, m -> draft = draft.copy(temperature = t, topK = k, maxOutputTokens = m) },
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { scope.launch { sheet.hide(); onDismiss() } }) { Text("Cancel") }
                Button(
                    onClick = { scope.launch { sheet.hide(); onSave(draft); onDismiss() } },
                    enabled = draft.canSave,
                ) { Text(if (isNew) "Start chatting" else "Save") }
            }
        }
    }
}

/** Which model the companion talks through. A core choice, so it shows with dev tools off; readiness detail is dev-only. */
@Composable
private fun ModelPicker(selected: ModelId?, defaultModel: ModelId, models: List<ModelChoice>, onSelect: (ModelId?) -> Unit) {
    Column(Modifier.selectableGroup()) {
        Text("Model", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 4.dp))
        ModelRow(selected == null, "Default (${defaultModel.label})", "Follows the app's default model.", null) { onSelect(null) }
        models.forEach { m -> ModelRow(selected == m.id, m.id.label, m.id.blurb, m) { onSelect(m.id) } }
    }
}

@Composable
private fun ModelRow(selected: Boolean, title: String, subtitle: String, choice: ModelChoice?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.size(40.dp))
        Column(Modifier.padding(top = 9.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (choice != null) {
                DevOnly {
                    Text(
                        modelStatusLine(choice.id, choice.state, choice.info),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalChatColors.current.faint,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}
